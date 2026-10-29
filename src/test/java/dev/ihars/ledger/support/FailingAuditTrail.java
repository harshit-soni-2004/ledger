package dev.ihars.ledger.support;

import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit trail that, when armed, writes the event and then fails. The audit event is the last
 * write of every audited operation, so arming it proves that all earlier writes roll back.
 * Toggled through a method because the bean is a transactional proxy.
 */
public class FailingAuditTrail extends AuditTrail {

    public static final String FAILURE_MESSAGE = "audit store unavailable";

    private volatile boolean failing;

    FailingAuditTrail(JdbcClient jdbc, JsonMapper json, Clock clock) {
        super(jdbc, json, clock);
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    @Override
    public void append(AuditEvent event) {
        super.append(event);
        if (failing) {
            throw new IllegalStateException(FAILURE_MESSAGE);
        }
    }

    /** Import into a test class to replace the audit trail with this one. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        @Primary
        FailingAuditTrail failingAuditTrail(JdbcClient jdbc, JsonMapper json, Clock clock) {
            return new FailingAuditTrail(jdbc, json, clock);
        }
    }
}
