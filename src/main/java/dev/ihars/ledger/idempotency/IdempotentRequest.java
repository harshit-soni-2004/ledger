package dev.ihars.ledger.idempotency;

import java.net.URI;
import java.util.function.Function;

/**
 * One mutating request guarded by an {@code Idempotency-Key}.
 *
 * @param clientId internal id of the authenticated client; keys are scoped per client
 * @param operation stable operation name, e.g. {@code OPEN_ACCOUNT}
 * @param key the raw header value (validated by the service)
 * @param fingerprint everything that defines the request (path ids and body); serialized to JSON
 *     and hashed, so the same key with a different request is detected
 * @param location builds the {@code Location} header from the stored resource id, or null
 */
public record IdempotentRequest(long clientId, String operation, String key, Object fingerprint,
        Function<String, URI> location) {}
