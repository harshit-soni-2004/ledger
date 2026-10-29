package dev.ihars.ledger.security;

import dev.ihars.ledger.audit.Actor;

/** What an API client may do (ADR-0009). Each client has exactly one role. */
public enum ApiRole {
    CLIENT,
    OPERATOR,
    ADMIN;

    public Actor.Type actorType() {
        return Actor.Type.valueOf(name());
    }

    /** Spring Security authority name. */
    public String authority() {
        return "ROLE_" + name();
    }
}
