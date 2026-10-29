package dev.ihars.ledger.ledger.posting;

import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.audit.AuditEvent;
import java.util.Objects;

/**
 * Who is posting and under which request; copied onto the journal header and the audit event.
 *
 * @param requestId the inbound request id, or null for internal callers
 * @param correlationId ties related journals together (e.g. a transfer and its fee), or null
 */
public record PostingContext(Actor actor, String requestId, String correlationId) {

    public PostingContext {
        Objects.requireNonNull(actor, "actor");
        AuditEvent.requireTraceId("requestId", requestId);
        AuditEvent.requireTraceId("correlationId", correlationId);
    }
}
