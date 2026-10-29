package dev.ihars.ledger.ledger.posting;

import dev.ihars.ledger.ledger.domain.AccountSnapshot;
import dev.ihars.ledger.ledger.domain.AccountStatus;
import dev.ihars.ledger.ledger.infrastructure.JdbcLedgerWriter;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registers new accounts with the ledger and changes their status under the posting lock. */
@Component
public class LedgerAccounts {

    private final JdbcLedgerWriter writer;

    public LedgerAccounts(JdbcLedgerWriter writer) {
        this.writer = writer;
    }

    /**
     * Must run in the transaction that inserts the account.
     *
     * @param minBalance the floor derived from the overdraft policy; null means no floor
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void register(long accountId, CurrencyUnit currency, Money minBalance, Instant now) {
        if (minBalance != null && !minBalance.currency().equals(currency)) {
            throw new IllegalArgumentException("Minimum balance must be in " + currency);
        }
        writer.insertBalance(accountId, currency, minBalance, now);
    }

    /**
     * Locks the account's balance row exactly as posting does, so a lifecycle change and a
     * posting on the same account serialize. Held until the caller's transaction ends.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AccountSnapshot> lockForLifecycle(long accountId) {
        return writer.lockAccount(accountId);
    }

    /**
     * Changes the status of an account locked with {@link #lockForLifecycle} in this transaction.
     * System accounts are never changed.
     *
     * @return false if the status was no longer {@code expected}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean changeStatus(long accountId, AccountStatus expected, AccountStatus target, Instant now) {
        return writer.updateStatus(accountId, expected, target, now);
    }
}
