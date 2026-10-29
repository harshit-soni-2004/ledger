package dev.ihars.ledger.security;

/** A client or key management request that cannot be carried out. Nothing is written. */
public class ApiClientRejectedException extends RuntimeException {

    public enum Code {
        INVALID_NAME,
        INVALID_ROLE,
        CLIENT_NOT_FOUND,
        KEY_NOT_FOUND,
        CLIENT_DISABLED,
        TOO_MANY_ACTIVE_KEYS
    }

    private final Code code;

    public ApiClientRejectedException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
