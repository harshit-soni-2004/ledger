package dev.ihars.ledger.ledger.domain;

import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.Money;
import java.util.Objects;

/**
 * An account's posting-relevant state, read under the balance-row lock.
 *
 * @param balance balance in the account's normal orientation
 * @param minBalance lowest allowed balance, or {@code null} when there is no floor
 * @param lastEntrySeq {@code account_seq} of the account's latest entry (0 before the first)
 */
public record AccountSnapshot(
        long accountId,
        CurrencyUnit currency,
        Direction normalBalance,
        AccountStatus status,
        Money balance,
        Money minBalance,
        long lastEntrySeq) {

    public AccountSnapshot {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(normalBalance, "normalBalance");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(balance, "balance");
        if (!balance.currency().equals(currency) || (minBalance != null && !minBalance.currency().equals(currency))) {
            throw new IllegalArgumentException("Balance and minimum balance must be in the account currency");
        }
    }
}
