package dev.ihars.ledger.shared.web;

import dev.ihars.ledger.shared.money.Money;

/** Wire form of money: the amount as a decimal string with the currency's scale. */
public record MoneyJson(String value, String currency) {

    public static MoneyJson of(Money money) {
        return money == null ? null : new MoneyJson(money.toPlainString(), money.currency().code());
    }
}
