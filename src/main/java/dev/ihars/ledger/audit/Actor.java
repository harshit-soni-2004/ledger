package dev.ihars.ledger.audit;

import java.util.Objects;

/** Who performs an operation; recorded on journals ({@code posted_by}) and audit events. */
public record Actor(Type type, String id) {

    public static final int MAX_ID_LENGTH = 64;

    public enum Type {
        CLIENT,
        OPERATOR,
        ADMIN,
        SYSTEM
    }

    public Actor {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("Actor id must be 1-" + MAX_ID_LENGTH + " characters");
        }
    }

    public static Actor system(String id) {
        return new Actor(Type.SYSTEM, id);
    }
}
