package dev.ihars.ledger.shared.money;

/** An amount that cannot be represented exactly as money in its currency. Never rounded. */
public class InvalidAmountException extends RuntimeException {

    public enum Reason {
        /** Not a plain decimal: empty, signs, exponents, grouping separators, whitespace, etc. */
        MALFORMED,
        /** Zero or negative where a positive amount is required. */
        NOT_POSITIVE,
        /** More significant decimal places than the currency's minor units. */
        PRECISION_EXCEEDED,
        /** Integer part does not fit {@code DECIMAL(19,4)}. */
        OUT_OF_RANGE
    }

    private final Reason reason;

    public InvalidAmountException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
