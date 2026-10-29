package dev.ihars.ledger.ledger.domain;

public enum JournalType {
    TRANSFER,
    DEPOSIT,
    PAYOUT,
    REVERSAL,
    ADJUSTMENT,
    OPENING;

    /**
     * Reversals and adjustments must explain themselves (ADR-0008; also a database CHECK).
     * Openings too, enforced by the application only.
     */
    public boolean requiresReason() {
        return this == REVERSAL || this == ADJUSTMENT || this == OPENING;
    }
}
