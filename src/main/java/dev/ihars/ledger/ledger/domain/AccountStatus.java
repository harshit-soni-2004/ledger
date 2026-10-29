package dev.ihars.ledger.ledger.domain;

/** Only {@link #ACTIVE} accounts accept postings. */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
