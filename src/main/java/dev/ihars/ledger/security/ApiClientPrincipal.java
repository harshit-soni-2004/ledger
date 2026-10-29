package dev.ihars.ledger.security;

import dev.ihars.ledger.audit.Actor;

/**
 * The authenticated caller. Holds only non-secret identifiers.
 *
 * @param clientId internal id, used for ownership and idempotency scope; never exposed
 */
public record ApiClientPrincipal(long clientId, String clientPublicId, ApiRole role, String keyPublicId) {

    public Actor actor() {
        return new Actor(role.actorType(), clientPublicId);
    }

    public boolean is(ApiRole other) {
        return role == other;
    }
}
