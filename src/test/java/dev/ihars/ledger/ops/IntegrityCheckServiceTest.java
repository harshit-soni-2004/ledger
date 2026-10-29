package dev.ihars.ledger.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.ledger.integrity.IntegrityChecker;
import dev.ihars.ledger.ledger.integrity.IntegrityReport;
import dev.ihars.ledger.ledger.integrity.IntegrityReport.Check;
import dev.ihars.ledger.ledger.integrity.IntegrityReport.Violation;
import dev.ihars.ledger.ops.infrastructure.JdbcIntegrityRunRepository;
import dev.ihars.ledger.shared.id.PublicIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class IntegrityCheckServiceTest {

    private static final Actor ADMIN = new Actor(Actor.Type.ADMIN, "cli_admin");

    private final IntegrityChecker checker = mock(IntegrityChecker.class);
    private final JdbcIntegrityRunRepository runs = mock(JdbcIntegrityRunRepository.class);
    private final PublicIdGenerator ids = mock(PublicIdGenerator.class);
    private final IntegrityCheckService service = new IntegrityCheckService(checker, runs, mock(AuditTrail.class), ids,
            Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC), mock(PlatformTransactionManager.class));

    @Test
    void aSecondRunWhileOneIsInProgressIsRefused() throws Exception {
        when(ids.next(any())).thenReturn("ichk_1", "ichk_2");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(checker.check()).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new IntegrityReport(List.of());
        }).thenReturn(new IntegrityReport(List.of()));

        CompletableFuture<IntegrityCheckRun> first = CompletableFuture.supplyAsync(
                () -> service.run(IntegrityCheckRun.Trigger.MANUAL, ADMIN, null));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.run(IntegrityCheckRun.Trigger.MANUAL, ADMIN, null))
                .isInstanceOf(IntegrityCheckService.CheckInProgressException.class);
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).publicId()).isEqualTo("ichk_1");

        assertThat(service.run(IntegrityCheckRun.Trigger.SCHEDULED, ADMIN, null).publicId()).isEqualTo("ichk_2");
    }

    @Test
    void storedViolationsAreCappedButTheCountIsComplete() {
        when(ids.next(any())).thenReturn("ichk_1");
        List<Violation> many = IntStream.range(0, IntegrityCheckService.MAX_STORED_VIOLATIONS + 5)
                .mapToObj(i -> new Violation(Check.PROJECTION_DRIFT, "acct_" + i))
                .toList();
        when(checker.check()).thenReturn(new IntegrityReport(many));

        IntegrityCheckRun run = service.run(IntegrityCheckRun.Trigger.MANUAL, ADMIN, null);

        assertThat(run.violationCount()).isEqualTo(many.size());
        assertThat(run.violations()).hasSize(IntegrityCheckService.MAX_STORED_VIOLATIONS);
        assertThat(run.isClean()).isFalse();
        verify(runs).insert(run);
    }
}
