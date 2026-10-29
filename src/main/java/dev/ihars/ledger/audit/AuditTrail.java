package dev.ihars.ledger.audit;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends audit events. Always joins the caller's transaction, so an event exists if and only
 * if the change it records was committed.
 */
@Component
public class AuditTrail {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public AuditTrail(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditEvent event) {
        jdbc.sql("""
                INSERT INTO audit_events (occurred_at, actor_type, actor_id, action, entity_type, entity_id,
                                          request_id, correlation_id, payload)
                VALUES (:occurredAt, :actorType, :actorId, :action, :entityType, :entityId,
                        :requestId, :correlationId, :payload)
                """)
                .param("occurredAt", LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))
                .param("actorType", event.actor().type().name())
                .param("actorId", event.actor().id())
                .param("action", event.action())
                .param("entityType", event.entityType())
                .param("entityId", event.entityId())
                .param("requestId", event.requestId())
                .param("correlationId", event.correlationId())
                .param("payload", json.writeValueAsString(event.payload()))
                .update();
    }
}
