package dev.ihars.ledger.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.ledger.integrity.IntegrityReport.Check;
import dev.ihars.ledger.ledger.integrity.IntegrityReport.Violation;
import dev.ihars.ledger.shared.id.PublicIdGenerator;
import dev.ihars.ledger.shared.id.PublicIdGenerator.Prefix;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class IntegrityCheckerIT extends LedgerIntegrationTest {

    @Autowired
    PublicIdGenerator ids;

    @Test
    void aLedgerWrittenByThePostingEngineIsClean() {
        Account alice = data.wallet("INR");
        Account bob = data.wallet("INR");
        data.deposit(alice, "50.00");
        data.transfer(alice, bob, "20.00");
        data.deposit(data.wallet("KWD"), "0.125");

        assertThat(integrity.check().violations()).isEmpty();
    }

    /**
     * Corrupts the ledger in an uncommitted root transaction, runs the checker on that same
     * connection, then rolls back. The shared database never sees the corruption.
     */
    @Test
    void everyKindOfCorruptionIsDetected() throws SQLException {
        Account drifted = data.wallet("INR");
        Account oneSided = data.wallet("INR");
        Account gapped = data.wallet("INR");
        Account counterparty = data.wallet("INR");
        Account subCent = data.wallet("INR");
        Account loosenedFloor = data.wallet("INR");
        data.deposit(drifted, "10.00");

        try (Connection connection = privilegedDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcClient root = JdbcClient.create(new SingleConnectionDataSource(connection, true));

                root.sql("UPDATE account_balances SET balance = balance + 1 WHERE account_id = ?")
                        .param(drifted.id()).update();

                long unbalanced = journal(root, "5.00");
                entry(root, unbalanced, 1, oneSided, "DEBIT", "5.00", 1, "-5.00");

                long broken = journal(root, "1.00");
                entry(root, broken, 1, gapped, "CREDIT", "1.00", 2, "7.00");
                entry(root, broken, 2, counterparty, "DEBIT", "1.00", 1, "-1.00");

                long subUnit = journal(root, "0.005");
                entry(root, subUnit, 1, subCent, "CREDIT", "0.005", 1, "0.005");
                entry(root, subUnit, 2, counterparty, "DEBIT", "0.005", 2, "-1.005");

                root.sql("UPDATE account_balances SET min_balance = -5 WHERE account_id = ?")
                        .param(loosenedFloor.id()).update();

                String orphan = ids.next(Prefix.ACCOUNT);
                root.sql("""
                        INSERT INTO accounts (public_id, name, type, normal_balance, currency, overdraft_policy,
                                              created_at, updated_at)
                        VALUES (?, 'orphan', 'LIABILITY', 'CREDIT', 'INR', 'FORBIDDEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """).param(orphan).update();

                IntegrityReport report = new IntegrityChecker(root).check();

                assertThat(report.violations()).contains(
                        new Violation(Check.PROJECTION_DRIFT, drifted.publicId()),
                        new Violation(Check.TRIAL_BALANCE, "balances:INR"),
                        new Violation(Check.JOURNAL_UNBALANCED, publicIdOf(root, unbalanced)),
                        new Violation(Check.TRIAL_BALANCE, "entries:INR"),
                        new Violation(Check.PROJECTION_DRIFT, oneSided.publicId()),
                        new Violation(Check.SEQUENCE_GAP, gapped.publicId()),
                        new Violation(Check.BALANCE_CHAIN_BROKEN, gapped.publicId() + "#2"),
                        new Violation(Check.SCALE_VIOLATION, "journal:" + publicIdOf(root, subUnit)),
                        new Violation(Check.FLOOR_MISMATCH, loosenedFloor.publicId()),
                        new Violation(Check.MISSING_BALANCE_ROW, orphan));
                assertThat(report.violations()).extracting(Violation::check).contains(Check.values());
            } finally {
                connection.rollback();
            }
        }

        assertThat(integrity.check().violations()).isEmpty();
    }

    private long journal(JdbcClient root, String total) {
        String publicId = ids.next(Prefix.JOURNAL);
        root.sql("""
                INSERT INTO journal_transactions (public_id, type, currency, total_debit, total_credit, reason,
                        effective_date, posted_at, posted_by)
                VALUES (?, 'ADJUSTMENT', 'INR', ?, ?, 'integrity test', UTC_DATE(), UTC_TIMESTAMP(6), 'test')
                """).param(publicId).param(total).param(total).update();
        return root.sql("SELECT id FROM journal_transactions WHERE public_id = ?")
                .param(publicId).query(Long.class).single();
    }

    private static void entry(JdbcClient root, long journalId, int lineNo, Account account, String direction,
            String amount, long seq, String balanceAfter) {
        root.sql("""
                INSERT INTO ledger_entries (journal_id, line_no, account_id, currency, direction, amount, account_seq,
                        balance_after, effective_date, posted_at)
                VALUES (?, ?, ?, 'INR', ?, ?, ?, ?, UTC_DATE(), UTC_TIMESTAMP(6))
                """)
                .param(journalId).param(lineNo).param(account.id()).param(direction).param(amount).param(seq)
                .param(balanceAfter)
                .update();
    }

    private static String publicIdOf(JdbcClient root, long journalId) {
        return root.sql("SELECT public_id FROM journal_transactions WHERE id = ?")
                .param(journalId).query(String.class).single();
    }
}
