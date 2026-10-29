package dev.ihars.ledger.security;

import java.time.Instant;

/** Stored key metadata. The token itself is never stored or returned after creation. */
public record ApiKey(String publicId, String clientPublicId, String prefix, Status status, Instant createdAt,
        Instant revokedAt) {

    public enum Status {
        ACTIVE,
        REVOKED
    }
}
