package dev.ihars.ledger.accounts;

/** An account request that fails validation. Nothing is written when this is thrown. */
public class AccountRejectedException extends RuntimeException {

    public enum Code {
        INVALID_NAME,
        INVALID_TYPE,
        UNSUPPORTED_CURRENCY,
        INVALID_OVERDRAFT,
        ACCOUNT_NOT_FOUND,
        SYSTEM_ACCOUNT,
        STATUS_CONFLICT,
        BALANCE_NOT_ZERO,
        REASON_REQUIRED
    }

    private final Code code;

    public AccountRejectedException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public AccountRejectedException(Code code, String message) {
        this(code, message, null);
    }

    public Code code() {
        return code;
    }
}
