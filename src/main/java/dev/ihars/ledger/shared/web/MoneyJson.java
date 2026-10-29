package dev.ihars.ledger.shared.web;

import dev.ihars.ledger.shared.money.Money;
import jakarta.validation.constraints.NotNull;

/** Wire form of money: the amount as a decimal string with the currency's scale. */
public record MoneyJson(@NotNull String value, @NotNull String currency) {

    public static MoneyJson of(Money money) {
        return money == null ? null : new MoneyJson(money.toPlainString(), money.currency().code());
    }
}
