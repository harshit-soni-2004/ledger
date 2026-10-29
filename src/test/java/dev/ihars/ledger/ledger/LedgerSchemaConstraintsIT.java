package dev.ihars.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.ledger.posting.PostedJournal;
import dev.ihars.ledger.shared.id.PublicIdGenerator;
import dev.ihars.ledger.shared.id.PublicIdGenerator.Prefix;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database rejects invalid ledger rows on its own, independently of the application
 * (docs/03-database-schema.md). Every probe runs as root in a transaction that is rolled back,
 * so a missing constraint fails the test without leaving data behind.
 */
class LedgerSchemaConstraintsIT extends LedgerIntegrationTest {

    private static final String JOURNAL = """
            INSERT INTO journal_transactions (public_id, type, currency, total_debit, total_credit,
                    reverses_journal_id, reason, effective_date, posted_at, posted_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, UTC_DATE(), UTC_TIMESTAMP(6), 'constraint-test')
            """;
    private static final String ENTRY = """
            INSERT INTO ledger_entries (journal_id, line_no, account_id, currency, direction, amount, account_seq,
                    balance_after, effective_date, posted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, UTC_DATE(), UTC_TIMESTAMP(6))
            """;
    private static final String ACCOUNT = """
            INSERT INTO accounts (public_id, name, type, normal_balance, currency, overdraft_policy, overdraft_limit,
                    created_at, updated_at)
            VALUES (?, 'probe', ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """;

    @Autowired
    PublicIdGenerator ids;

    private Account inr;
    private Account dollars;
    private PostedJournal journal;

    @BeforeEach
    void postAJournal() {
        inr = data.wallet("INR");
        dollars = data.wallet("USD");
        journal = data.deposit(inr, "10.00");
    }

    @Test
    void journalHeadersMustBalanceAndBePositive() {
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement(JOURNAL, jtx(), "TRANSFER", "INR", "10", "9.99", null, null));
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement(JOURNAL, jtx(), "TRANSFER", "INR", "0", "0", null, null));
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement(JOURNAL, jtx(), "TRANSFER", "INR", "-5", "-5", null, null));
    }

    @Test
    void reversalsMustLinkToOneJournalInTheSameCurrency() {
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement(JOURNAL, jtx(), "REVERSAL", "INR", "10", "10", null, "r"));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(JOURNAL, jtx(), "TRANSFER", "INR", "10", "10", journal.id(), null));
        assertRejected(FOREIGN_KEY_VIOLATED,
                statement(JOURNAL, jtx(), "REVERSAL", "USD", "10", "10", journal.id(), "r"));
        assertRejected(DUPLICATE_KEY,
                statement(JOURNAL, jtx(), "REVERSAL", "INR", "10", "10", journal.id(), "first"),
                statement(JOURNAL, jtx(), "REVERSAL", "INR", "10", "10", journal.id(), "second"));
    }

    @Test
    void reversalsAndAdjustmentsRequireANonBlankReason() {
        for (Object reason : new Object[] {null, "", "   "}) {
            assertRejected(CHECK_CONSTRAINT_VIOLATED,
                    statement(JOURNAL, jtx(), "ADJUSTMENT", "INR", "1", "1", null, reason));
        }
    }

    @Test
    void publicIdsMustBePrefixedUlids() {
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(JOURNAL, "jtx_not-a-ulid", "TRANSFER", "INR", "1", "1", null, null));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(JOURNAL, ids.next(Prefix.ACCOUNT), "TRANSFER", "INR", "1", "1", null, null));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(ACCOUNT, jtx(), "LIABILITY", "CREDIT", "INR", "FORBIDDEN", null));
    }

    @Test
    void anEntryHasTheCurrencyOfItsJournalAndOfItsAccount() {
        assertRejected(FOREIGN_KEY_VIOLATED,
                statement(ENTRY, journal.id(), 3, dollars.id(), "USD", "DEBIT", "1", 1));
        assertRejected(FOREIGN_KEY_VIOLATED,
                statement(ENTRY, journal.id(), 3, dollars.id(), "INR", "DEBIT", "1", 1));
    }

    @Test
    void entriesArePositiveAndUniquelySequenced() {
        Account other = data.wallet("INR");
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement(ENTRY, journal.id(), 3, other.id(), "INR", "DEBIT", "0", 1));
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement(ENTRY, journal.id(), 3, other.id(), "INR", "DEBIT", "-1", 1));
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement(ENTRY, journal.id(), 3, other.id(), "INR", "DEBIT", "1", 0));
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement(ENTRY, journal.id(), 0, other.id(), "INR", "DEBIT", "1", 1));
        assertRejected(DUPLICATE_KEY, statement(ENTRY, journal.id(), 3, inr.id(), "INR", "DEBIT", "1", 1));
        assertRejected(DUPLICATE_KEY, statement(ENTRY, journal.id(), 1, other.id(), "INR", "DEBIT", "1", 1));
    }

    @Test
    void balancesCannotFallBelowTheirFloor() {
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement("UPDATE account_balances SET balance = -0.01 WHERE account_id = ?", dollars.id()));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement("UPDATE account_balances SET min_balance = 5 WHERE account_id = ?", dollars.id()));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement("UPDATE account_balances SET last_entry_seq = -1 WHERE account_id = ?", dollars.id()));
    }

    @Test
    void accountsHaveAConsistentNormalBalanceAndOverdraftPolicy() {
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(ACCOUNT, ids.next(Prefix.ACCOUNT), "ASSET", "CREDIT", "INR", "FORBIDDEN", null));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(ACCOUNT, ids.next(Prefix.ACCOUNT), "LIABILITY", "DEBIT", "INR", "FORBIDDEN", null));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(ACCOUNT, ids.next(Prefix.ACCOUNT), "LIABILITY", "CREDIT", "INR", "LIMITED", null));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(ACCOUNT, ids.next(Prefix.ACCOUNT), "LIABILITY", "CREDIT", "INR", "LIMITED", "0"));
        assertRejected(CHECK_CONSTRAINT_VIOLATED,
                statement(ACCOUNT, ids.next(Prefix.ACCOUNT), "LIABILITY", "CREDIT", "INR", "FORBIDDEN", "10"));
        assertRejected(FOREIGN_KEY_VIOLATED,
                statement(ACCOUNT, ids.next(Prefix.ACCOUNT), "LIABILITY", "CREDIT", "XXX", "FORBIDDEN", null));
    }

    @Test
    void auditActionsAreUpperSnakeCase() {
        assertRejected(CHECK_CONSTRAINT_VIOLATED, statement("""
                INSERT INTO audit_events (occurred_at, actor_type, actor_id, action, entity_type, entity_id)
                VALUES (UTC_TIMESTAMP(6), 'SYSTEM', 'test', 'journal posted', 'JOURNAL', 'x')
                """));
    }

    private String jtx() {
        return ids.next(Prefix.JOURNAL);
    }

    private record Statement(String sql, List<Object> params) {}

    private static Statement statement(String sql, Object... params) {
        return new Statement(sql, java.util.Arrays.asList(params));
    }

    /** Runs the statements in one rolled-back transaction; the last one must fail with {@code errorCode}. */
    private static void assertRejected(int errorCode, Statement... statements) {
        DataSource root = privilegedDataSource();
        JdbcClient jdbc = JdbcClient.create(root);
        TransactionTemplate rollbackOnly = new TransactionTemplate(new DataSourceTransactionManager(root));
        rollbackOnly.executeWithoutResult(status -> {
            status.setRollbackOnly();
            for (int i = 0; i < statements.length - 1; i++) {
                run(jdbc, statements[i]);
            }
            Statement last = statements[statements.length - 1];
            assertThatThrownBy(() -> run(jdbc, last))
                    .as(last.sql() + " " + last.params())
                    .isInstanceOf(DataAccessException.class)
                    .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(errorCode));
        });
    }

    private static void run(JdbcClient jdbc, Statement statement) {
        var spec = jdbc.sql(statement.sql());
        for (Object param : statement.params()) {
            spec = spec.param(param);
        }
        spec.update();
    }
}
