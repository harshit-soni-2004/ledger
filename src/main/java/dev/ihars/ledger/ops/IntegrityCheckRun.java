package dev.ihars.ledger.ops;

import dev.ihars.ledger.ledger.integrity.IntegrityReport.Violation;
import java.time.Instant;
import java.util.List;

/**
 * A completed integrity check as stored (append-only).
 *
 * @param violationCount all violations found; {@code violations} holds at most
 *     {@link IntegrityCheckService#MAX_STORED_VIOLATIONS} of them
 */
public record IntegrityCheckRun(String publicId, Trigger trigger, String triggeredBy, Instant startedAt,
        Instant finishedAt, int violationCount, List<Violation> violations) {

    public enum Trigger {
        MANUAL,
        SCHEDULED
    }

    public IntegrityCheckRun {
        violations = List.copyOf(violations);
    }

    public boolean isClean() {
        return violationCount == 0;
    }
}
