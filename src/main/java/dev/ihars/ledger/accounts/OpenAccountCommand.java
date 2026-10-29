package dev.ihars.ledger.accounts;

import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;

/**
 * Request to open an account. Raw values are validated by {@link AccountService#open}.
 *
 * @param currency ISO 4217 code of a supported, active currency
 * @param overdraftLimit decimal string, required exactly when {@code overdraft} is LIMITED
 */
public record OpenAccountCommand(String name, AccountType type, String currency, OverdraftPolicy.Kind overdraft,
        String overdraftLimit) {

    /** A customer wallet: a liability that may not go below zero. */
    public static OpenAccountCommand wallet(String name, String currency) {
        return new OpenAccountCommand(name, AccountType.LIABILITY, currency, OverdraftPolicy.Kind.FORBIDDEN, null);
    }
}
