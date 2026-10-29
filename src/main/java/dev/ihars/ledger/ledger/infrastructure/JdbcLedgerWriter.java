package dev.ihars.ledger.ledger.infrastructure;

import dev.ihars.ledger.ledger.domain.AccountSnapshot;
import dev.ihars.ledger.ledger.domain.AccountStatus;
import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.PostingPlan.BalanceChange;
import dev.ihars.ledger.ledger.domain.PostingPlan.PlannedEntry;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * All SQL that changes ledger state. Only {@code ledger.posting} may use this class
 * (enforced by ArchitectureTest), and every method requires the caller's transaction.
 */
@Repository
public class JdbcLedgerWriter {

    private static final String LOCK_BALANCE = """
            SELECT b.account_id, b.currency, c.minor_units, b.balance, b.min_balance, b.last_entry_seq,
                   a.normal_balance, a.status
              FROM account_balances b
              JOIN accounts a ON a.id = b.account_id
              JOIN currencies c ON c.code = b.currency
             WHERE b.account_id = :accountId
               FOR UPDATE OF b
            """;

    private final JdbcClient jdbc;

    public JdbcLedgerWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the balance rows of the given accounts, one statement per account in ascending id
     * order (ADR-0005), and returns their state as read under the lock. Missing accounts are
     * absent from the result.
     */
    public Map<Long, AccountSnapshot> lockAccounts(SortedSet<Long> accountIds) {
        Map<Long, AccountSnapshot> snapshots = new LinkedHashMap<>();
        for (long accountId : accountIds) {
            Optional<AccountSnapshot> snapshot = jdbc.sql(LOCK_BALANCE)
                    .param("accountId", accountId)
                    .query((rs, n) -> {
                        CurrencyUnit currency = new CurrencyUnit(rs.getString("currency"), rs.getInt("minor_units"));
                        BigDecimal minBalance = rs.getBigDecimal("min_balance");
                        return new AccountSnapshot(rs.getLong("account_id"), currency,
                                Direction.valueOf(rs.getString("normal_balance")),
                                AccountStatus.valueOf(rs.getString("status")),
                                Money.of(rs.getBigDecimal("balance"), currency),
                                minBalance == null ? null : Money.of(minBalance, currency),
                                rs.getLong("last_entry_seq"));
                    })
                    .optional();
            snapshot.ifPresent(s -> snapshots.put(accountId, s));
        }
        return snapshots;
    }

    /** Inserts the journal header and returns its generated id. */
    public long insertJournal(String publicId, JournalDraft draft, Instant postedAt, String postedBy,
            String requestId, String correlationId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO journal_transactions (public_id, type, currency, total_debit, total_credit,
                        reverses_journal_id, external_reference, description, reason, effective_date,
                        posted_at, posted_by, request_id, correlation_id)
                VALUES (:publicId, :type, :currency, :total, :total, :reverses, :externalReference,
                        :description, :reason, :effectiveDate, :postedAt, :postedBy, :requestId, :correlationId)
                """)
                .param("publicId", publicId)
                .param("type", draft.type().name())
                .param("currency", draft.currency().code())
                .param("total", draft.total().amount())
                .param("reverses", draft.reversesJournalId())
                .param("externalReference", draft.externalReference())
                .param("description", draft.description())
                .param("reason", draft.reason())
                .param("effectiveDate", draft.effectiveDate())
                .param("postedAt", utc(postedAt))
                .param("postedBy", postedBy)
                .param("requestId", requestId)
                .param("correlationId", correlationId)
                .update(keys, "id");
        return keys.getKeyAs(Number.class).longValue();
    }

    public void insertEntries(long journalId, JournalDraft draft, List<PlannedEntry> entries, Instant postedAt) {
        for (PlannedEntry entry : entries) {
            jdbc.sql("""
                    INSERT INTO ledger_entries (journal_id, line_no, account_id, currency, direction, amount,
                            account_seq, balance_after, effective_date, posted_at)
                    VALUES (:journalId, :lineNo, :accountId, :currency, :direction, :amount,
                            :accountSeq, :balanceAfter, :effectiveDate, :postedAt)
                    """)
                    .param("journalId", journalId)
                    .param("lineNo", entry.lineNo())
                    .param("accountId", entry.accountId())
                    .param("currency", draft.currency().code())
                    .param("direction", entry.direction().name())
                    .param("amount", entry.amount().amount())
                    .param("accountSeq", entry.accountSeq())
                    .param("balanceAfter", entry.balanceAfter().amount())
                    .param("effectiveDate", draft.effectiveDate())
                    .param("postedAt", utc(postedAt))
                    .update();
        }
    }

    /**
     * Applies a projection change. The {@code last_entry_seq} guard turns a lost update (which
     * the lock should make impossible) into a failure instead of silent drift.
     */
    public void updateBalance(BalanceChange change, Instant updatedAt) {
        int updated = jdbc.sql("""
                UPDATE account_balances
                   SET balance = :balance, last_entry_seq = :newSeq, updated_at = :updatedAt
                 WHERE account_id = :accountId AND last_entry_seq = :previousSeq
                """)
                .param("balance", change.newBalance().amount())
                .param("newSeq", change.newLastSeq())
                .param("updatedAt", utc(updatedAt))
                .param("accountId", change.accountId())
                .param("previousSeq", change.previousLastSeq())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Balance of account %d changed while locked".formatted(change.accountId()));
        }
    }

    /** Creates the zero balance row of a newly opened account. */
    public void insertBalance(long accountId, CurrencyUnit currency, Money minBalance, Instant now) {
        jdbc.sql("""
                INSERT INTO account_balances (account_id, currency, balance, min_balance, last_entry_seq, updated_at)
                VALUES (:accountId, :currency, 0, :minBalance, 0, :updatedAt)
                """)
                .param("accountId", accountId)
                .param("currency", currency.code())
                .param("minBalance", minBalance == null ? null : minBalance.amount())
                .param("updatedAt", utc(now))
                .update();
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
