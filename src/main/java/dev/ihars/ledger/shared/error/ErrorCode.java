package dev.ihars.ledger.shared.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatusCode;

/**
 * Stable, client-facing error codes returned in the {@code code} property of every Problem
 * Details response. The catalogue grows phase by phase; see docs/04-api-contract.md.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    BAD_REQUEST,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    NOT_ACCEPTABLE,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    INTERNAL_ERROR,
    RESOURCE_BUSY,

    AMOUNT_PRECISION_EXCEEDED,
    IDEMPOTENCY_KEY_MISSING,
    IDEMPOTENCY_KEY_INVALID,
    IDEMPOTENCY_KEY_REUSED,
    REQUEST_IN_PROGRESS,

    UNSUPPORTED_CURRENCY,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_NOT_ACTIVE,
    ACCOUNT_STATUS_CONFLICT,
    ACCOUNT_BALANCE_NOT_ZERO,
    INSUFFICIENT_FUNDS,
    CURRENCY_MISMATCH,
    EFFECTIVE_DATE_IN_FUTURE,
    OPENING_NOT_ALLOWED,
    JOURNAL_REJECTED,
    TRANSFER_NOT_ALLOWED,

    CLIENT_DISABLED,
    TOO_MANY_ACTIVE_KEYS,
    INTEGRITY_CHECK_IN_PROGRESS;

    private static final String TYPE_BASE = "https://ledger.local/problems/";

    public URI type() {
        return URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    static ErrorCode fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
