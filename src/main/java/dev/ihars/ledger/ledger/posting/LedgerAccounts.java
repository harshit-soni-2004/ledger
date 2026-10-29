package dev.ihars.ledger.ledger.posting;

import dev.ihars.ledger.ledger.infrastructure.JdbcLedgerWriter;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registers new accounts with the ledger by creating their balance projection row. */
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
}
