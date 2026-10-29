package dev.ihars.ledger.shared.web;

import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * One page of a keyset-paginated list. {@code nextCursor} is opaque to clients and null on the
 * last page.
 */
public record Page<T>(List<T> data, String nextCursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    private static final int MAX_CURSOR_LENGTH = 128;

    public Page {
        data = List.copyOf(data);
    }

    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return requested;
    }

    public static String encodeCursor(String position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
    }

    /** The position inside a cursor, or null when no cursor was sent. */
    public static String decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
                throw new IllegalArgumentException();
            }
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    public static ApiProblemException invalidCursor() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "The cursor is not valid.");
    }
}
