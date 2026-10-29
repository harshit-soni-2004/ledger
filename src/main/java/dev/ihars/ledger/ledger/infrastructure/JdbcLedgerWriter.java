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

    // normal_balance and currency are immutable, so the non-locking read of accounts and
    // currencies in this statement cannot be stale.
    private static final String LOCK_BALANCE = """
            SELECT b.account_id, b.currency, c.minor_units, b.balance, b.min_balance, b.last_entry_seq,
                   a.normal_balance
              FROM account_balances b
              JOIN accounts a ON a.id = b.account_id
              JOIN currencies c ON c.code = b.currency
             WHERE b.account_id = :accountId
               FOR UPDATE OF b
            """;

    // Status is mutable. Lifecycle changes update it only while holding the balance-row lock, and
    // a locking read always returns the latest committed version, so reading it here, after the
    // balance lock is granted, cannot miss a freeze or close that committed while we waited.
    private static final String READ_STATUS_LOCKED = "SELECT status FROM accounts WHERE id = :accountId FOR SHARE";

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
            lockAccount(accountId).ifPresent(s -> snapshots.put(accountId, s));
        }
        return snapshots;
    }

    /** Locks one balance row and returns the account state as read under the lock. */
    public Optional<AccountSnapshot> lockAccount(long accountId) {
        return jdbc.sql(LOCK_BALANCE)
                .param("accountId", accountId)
                .query((rs, n) -> new LockedBalance(rs.getLong("account_id"),
                        new CurrencyUnit(rs.getString("currency"), rs.getInt("minor_units")),
                        Direction.valueOf(rs.getString("normal_balance")), rs.getBigDecimal("balance"),
                        rs.getBigDecimal("min_balance"), rs.getLong("last_entry_seq")))
                .optional()
                .map(locked -> {
                    AccountStatus status = AccountStatus.valueOf(jdbc.sql(READ_STATUS_LOCKED)
                            .param("accountId", accountId).query(String.class).single());
                    return locked.toSnapshot(status);
                });
    }

    /**
     * Changes the status of an account whose balance row the caller has locked.
     *
     * @return false if the status was no longer {@code expected}
     */
    public boolean updateStatus(long accountId, AccountStatus expected, AccountStatus target, Instant now) {
        return jdbc.sql("""
                UPDATE accounts SET status = :target, updated_at = :now
                 WHERE id = :accountId AND status = :expected AND system_code IS NULL
                """)
                .param("target", target.name())
                .param("now", utc(now))
                .param("accountId", accountId)
                .param("expected", expected.name())
                .update() == 1;
    }

    private record LockedBalance(long accountId, CurrencyUnit currency, Direction normalBalance, BigDecimal balance,
            BigDecimal minBalance, long lastEntrySeq) {
        AccountSnapshot toSnapshot(AccountStatus status) {
            return new AccountSnapshot(accountId, currency, normalBalance, status, Money.of(balance, currency),
                    minBalance == null ? null : Money.of(minBalance, currency), lastEntrySeq);
        }
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
