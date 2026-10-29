package dev.ihars.ledger.audit;

import java.util.Map;
import java.util.Objects;

/**
 * One audit record. {@code payload} must not contain secrets; amounts are written as strings.
 *
 * @param action upper snake case, e.g. {@code JOURNAL_POSTED}
 * @param entityId the public id of the affected entity
 * @param requestId the inbound request id, or null
 * @param correlationId groups related operations, or null
 */
public record AuditEvent(Actor actor, String action, String entityType, String entityId, String requestId,
        String correlationId, Map<String, Object> payload) {

    public static final int MAX_TRACE_ID_LENGTH = 64;

    public AuditEvent {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        requireTraceId("requestId", requestId);
        requireTraceId("correlationId", correlationId);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** Request and correlation ids are stored in ASCII columns of at most 64 characters. */
    public static void requireTraceId(String name, String value) {
        if (value != null && (value.isEmpty() || value.length() > MAX_TRACE_ID_LENGTH
                || !value.chars().allMatch(c -> c > ' ' && c < 127))) {
            throw new IllegalArgumentException(
                    name + " must be 1-" + MAX_TRACE_ID_LENGTH + " printable ASCII characters");
        }
    }
}
