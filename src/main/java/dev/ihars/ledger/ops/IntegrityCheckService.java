package dev.ihars.ledger.ops;

import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.ledger.integrity.IntegrityChecker;
import dev.ihars.ledger.ledger.integrity.IntegrityReport;
import dev.ihars.ledger.ops.infrastructure.JdbcIntegrityRunRepository;
import dev.ihars.ledger.shared.id.PublicIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the ledger integrity checks and records every run (append-only) with an audit event.
 *
 * <p>At most one run at a time per application instance; a second request while one is running
 * is refused rather than queued, because the checks are full scans. The check itself reads one
 * consistent snapshot and takes no locks, so it never blocks postings.
 */
@Service
public class IntegrityCheckService {

    public static final String INTEGRITY_CHECK_COMPLETED = "INTEGRITY_CHECK_COMPLETED";
    public static final int MAX_STORED_VIOLATIONS = 1000;
    private static final Logger log = LoggerFactory.getLogger(IntegrityCheckService.class);

    private final IntegrityChecker checker;
    private final JdbcIntegrityRunRepository runs;
    private final AuditTrail audit;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();

    public IntegrityCheckService(IntegrityChecker checker, JdbcIntegrityRunRepository runs, AuditTrail audit,
            PublicIdGenerator ids, Clock clock, PlatformTransactionManager transactions) {
        this.checker = checker;
        this.runs = runs;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Runs every check now.
     *
     * @throws CheckInProgressException if a run is already in progress on this instance
     */
    public IntegrityCheckRun run(IntegrityCheckRun.Trigger trigger, Actor actor, String requestId) {
        if (!running.compareAndSet(false, true)) {
            throw new CheckInProgressException();
        }
        try {
            Instant started = now();
            IntegrityReport report = checker.check();
            IntegrityCheckRun run = new IntegrityCheckRun(ids.next(PublicIdGenerator.Prefix.INTEGRITY_CHECK), trigger,
                    actor.id(), started, now(), report.violations().size(),
                    report.violations().subList(0, Math.min(MAX_STORED_VIOLATIONS, report.violations().size())));
            tx.executeWithoutResult(status -> {
                runs.insert(run);
                audit.append(new AuditEvent(actor, INTEGRITY_CHECK_COMPLETED, "INTEGRITY_CHECK", run.publicId(),
                        requestId, null, Map.of("trigger", trigger.name(), "violations", run.violationCount())));
            });
            if (run.isClean()) {
                log.info("Integrity check {} passed", run.publicId());
            } else {
                log.error("Integrity check {} found {} violations", run.publicId(), run.violationCount());
            }
            return run;
        } finally {
            running.set(false);
        }
    }

    public Optional<IntegrityCheckRun> latest() {
        return runs.latest();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public static class CheckInProgressException extends RuntimeException {
        CheckInProgressException() {
            super("An integrity check is already running");
        }
    }
}
