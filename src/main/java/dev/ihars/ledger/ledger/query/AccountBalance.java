package dev.ihars.ledger.ledger.query;

import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.util.Optional;

/**
 * Current balance of an account from the balance projection.
 *
 * <p>Balances are in the account's normal orientation (docs/02-accounting-model.md): positive
 * means "normal" (an asset holds money, a liability owes money). There are no holds yet, so
 * the ledger balance includes every posted entry.
 *
 * @param ledgerBalance the sum of all posted entries, signed by the account's normal balance
 * @param minBalance the floor from the overdraft policy, or null if the account has none
 * @param lastEntrySeq the sequence number of the latest entry, 0 if none
 */
public record AccountBalance(long accountId, CurrencyUnit currency, Direction normalBalance, Money ledgerBalance,
        Money minBalance, long lastEntrySeq) {

    /**
     * How much the balance may still decrease: {@code ledgerBalance - minBalance}. Empty for
     * accounts without a floor (system accounts with an unlimited overdraft).
     */
    public Optional<Money> available() {
        return minBalance == null ? Optional.empty() : Optional.of(ledgerBalance.minus(minBalance));
    }
}
