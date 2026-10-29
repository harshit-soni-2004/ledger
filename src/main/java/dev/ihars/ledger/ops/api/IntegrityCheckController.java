package dev.ihars.ledger.ops.api;

import dev.ihars.ledger.ledger.integrity.IntegrityReport.Check;
import dev.ihars.ledger.ops.IntegrityCheckRun;
import dev.ihars.ledger.ops.IntegrityCheckService;
import dev.ihars.ledger.security.ApiClientPrincipal;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.error.Problems;
import dev.ihars.ledger.shared.web.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only integrity checks. Results name rows by public id only, never amounts. */
@RestController
@RequestMapping("/api/v1/ops/integrity-checks")
@Tag(name = "Operations")
public class IntegrityCheckController {

    private final IntegrityCheckService service;

    public IntegrityCheckController(IntegrityCheckService service) {
        this.service = service;
    }

    public record ViolationResponse(Check check, String subject) {}

    public record RunResponse(String id, IntegrityCheckRun.Trigger trigger, String triggeredBy, Instant startedAt,
            Instant finishedAt, boolean clean, int violationCount, List<ViolationResponse> violations) {

        static RunResponse of(IntegrityCheckRun run) {
            return new RunResponse(run.publicId(), run.trigger(), run.triggeredBy(), run.startedAt(),
                    run.finishedAt(), run.isClean(), run.violationCount(),
                    run.violations().stream().map(v -> new ViolationResponse(v.check(), v.subject())).toList());
        }
    }

    @PostMapping
    @Operation(summary = "Run the integrity checks now (ADMIN)",
            description = "Full scans; 409 INTEGRITY_CHECK_IN_PROGRESS if a run is already in progress.")
    public RunResponse run(@AuthenticationPrincipal ApiClientPrincipal caller) {
        return RunResponse.of(service.run(IntegrityCheckRun.Trigger.MANUAL, caller.actor(),
                RequestContext.requestId()));
    }

    @GetMapping("/latest")
    @Operation(summary = "The most recent integrity check result (ADMIN)")
    public RunResponse latest() {
        return service.latest().map(RunResponse::of)
                .orElseThrow(() -> ApiProblemException.notFound("No integrity check has run yet."));
    }

    @ExceptionHandler(IntegrityCheckService.CheckInProgressException.class)
    ResponseEntity<ProblemDetail> inProgress() {
        return Problems.response(HttpStatus.CONFLICT, ErrorCode.INTEGRITY_CHECK_IN_PROGRESS,
                "An integrity check is already running; retry later.");
    }
}
