package dev.ihars.ledger.ledger.domain;

/** Account classes and their normal balances (docs/02-accounting-model.md). */
public enum AccountType {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT),
    EXPENSE(Direction.DEBIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    /** The side that increases this account's balance. */
    public Direction normalBalance() {
        return normalBalance;
    }
}
