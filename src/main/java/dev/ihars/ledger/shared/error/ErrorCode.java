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
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    NOT_ACCEPTABLE,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    INTERNAL_ERROR;

    private static final String TYPE_BASE = "https://ledger.local/problems/";

    public URI type() {
        return URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    static ErrorCode fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
