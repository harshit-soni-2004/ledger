package dev.ihars.ledger.ledger.domain;

/** A journal that violates an accounting rule. Nothing is posted when this is thrown. */
public class JournalRejectedException extends RuntimeException {

    public enum Code {
        TOO_FEW_LINES,
        TOO_MANY_LINES,
        CURRENCY_MISMATCH,
        UNBALANCED,
        SAME_ACCOUNT_BOTH_SIDES,
        REASON_REQUIRED,
        INVALID_FIELD,
        AMOUNT_OUT_OF_RANGE,
        CANNOT_REVERSE_REVERSAL,
        REVERSAL_NOT_SUPPORTED,
        EFFECTIVE_DATE_IN_FUTURE,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_NOT_ACTIVE,
        INSUFFICIENT_FUNDS,
        OPENING_NOT_ALLOWED
    }

    private final Code code;

    public JournalRejectedException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
