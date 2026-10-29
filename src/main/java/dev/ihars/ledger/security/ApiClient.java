package dev.ihars.ledger.security;

import java.time.Instant;

/** @param id internal id; never exposed outside the service */
public record ApiClient(long id, String publicId, String name, ApiRole role, Status status, Instant createdAt) {

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
