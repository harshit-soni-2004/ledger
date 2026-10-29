package dev.ihars.ledger.ledger.domain;

/** Side of a ledger entry. Also used as an account's normal balance. */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
