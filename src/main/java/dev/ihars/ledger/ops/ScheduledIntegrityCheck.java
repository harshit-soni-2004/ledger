package dev.ihars.ledger.ops;

import dev.ihars.ledger.audit.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Nightly integrity check in the business zone. Disable with {@code ledger.integrity.schedule.enabled=false}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "ledger.integrity.schedule.enabled", matchIfMissing = true)
public class ScheduledIntegrityCheck {

    private static final Logger log = LoggerFactory.getLogger(ScheduledIntegrityCheck.class);
    private static final Actor SCHEDULER = Actor.system("integrity-scheduler");

    private final IntegrityCheckService service;

    public ScheduledIntegrityCheck(IntegrityCheckService service) {
        this.service = service;
    }

    @Scheduled(cron = "${ledger.integrity.schedule.cron}", zone = "${ledger.business-zone}")
    void run() {
        try {
            service.run(IntegrityCheckRun.Trigger.SCHEDULED, SCHEDULER, null);
        } catch (IntegrityCheckService.CheckInProgressException e) {
            log.warn("Skipping scheduled integrity check: a run is already in progress");
        }
    }
}
