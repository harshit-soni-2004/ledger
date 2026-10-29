package dev.ihars.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.support.IntegrationTest;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class IdempotencyServiceIT extends IntegrationTest {

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private AuditTrail audit;

    private long clientId;
    private String entity;
    private final AtomicInteger executions = new AtomicInteger();
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        String publicId = newClient(ApiRole.CLIENT).id();
        clientId = privilegedJdbc().sql("SELECT id FROM api_clients WHERE public_id = ?").param(publicId)
                .query(Long.class).single();
        entity = "test_" + UUID.randomUUID().toString().substring(0, 8);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void aRetryReplaysTheStoredResponseWithoutRepeatingTheEffect() {
        IdempotentRequest request = request("k-1", Map.of("amount", "10.00"));

        ResponseEntity<String> first = idempotency.execute(request, this::effect);
        ResponseEntity<String> retry = idempotency.execute(request, this::effect);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getHeaders().getLocation()).isEqualTo(URI.create("/things/" + entity));
        assertThat(first.getHeaders().containsHeader(IdempotencyService.REPLAYED_HEADER)).isFalse();
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(retry.getHeaders().getLocation()).isEqualTo(URI.create("/things/" + entity));
        assertThat(retry.getBody()).contains("\"id\"").contains(entity);
        assertThat(executions).hasValue(1);
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentRequestIsRejected() {
        idempotency.execute(request("k-2", Map.of("amount", "10.00")), this::effect);

        assertThatThrownBy(() -> idempotency.execute(request("k-2", Map.of("amount", "11.00")), this::effect))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerOperationAndClient() {
        idempotency.execute(request("k-3", Map.of("amount", "10.00")), this::effect);
        idempotency.execute(new IdempotentRequest(clientId, "OTHER_OPERATION", "k-3", Map.of(), null), this::effect);

        assertThat(effects()).isEqualTo(2);
    }

    @Test
    void aFailedAttemptLeavesNoKeySoTheClientCanRetry() {
        IdempotentRequest request = request("k-4", Map.of("amount", "10.00"));

        assertThatThrownBy(() -> idempotency.execute(request, () -> {
            effect();
            throw new IllegalStateException("business rejection");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(effects()).isZero();

        ResponseEntity<String> retry = idempotency.execute(request, this::effect);
        assertThat(retry.getHeaders().containsHeader(IdempotencyService.REPLAYED_HEADER)).isFalse();
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void missingAndMalformedKeysAreRejectedBeforeAnyEffect() {
        assertThatThrownBy(() -> idempotency.execute(request(null, Map.of()), this::effect))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISSING));
        assertThatThrownBy(() -> idempotency.execute(request("bad key!", Map.of()), this::effect))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_INVALID));
        assertThatThrownBy(() -> idempotency.execute(request("x".repeat(129), Map.of()), this::effect))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_INVALID));
        assertThat(executions).hasValue(0);
    }

    @Test
    void aConcurrentDuplicateWaitsForTheFirstAttemptAndReplaysIt() throws Exception {
        IdempotentRequest request = request("k-5", Map.of("amount", "10.00"));
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<ResponseEntity<String>> first = pool.submit(() -> idempotency.execute(request, () -> {
            claimed.countDown();
            await(release);
            return effect();
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ResponseEntity<String>> duplicate = pool.submit(() -> idempotency.execute(request, this::effect));
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(duplicate.isDone()).as("duplicate blocks on the uncommitted claim").isFalse();
        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).getHeaders().containsHeader(IdempotencyService.REPLAYED_HEADER))
                .isFalse();
        assertThat(duplicate.get(10, TimeUnit.SECONDS).getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER))
                .isEqualTo("true");
        assertThat(executions).hasValue(1);
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void aDuplicateOfAnAttemptThatRollsBackRunsAsTheFirstAttempt() throws Exception {
        IdempotentRequest request = request("k-6", Map.of("amount", "10.00"));
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> failing = pool.submit(() -> idempotency.execute(request, () -> {
            claimed.countDown();
            await(release);
            throw new IllegalStateException("rejected");
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ResponseEntity<String>> duplicate = pool.submit(() -> idempotency.execute(request, this::effect));
        TimeUnit.MILLISECONDS.sleep(500);
        release.countDown();

        assertThatThrownBy(() -> failing.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(duplicate.get(10, TimeUnit.SECONDS).getHeaders().containsHeader(IdempotencyService.REPLAYED_HEADER))
                .isFalse();
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void aDuplicateThatWaitsTooLongIsToldTheRequestIsInProgress() throws Exception {
        IdempotentRequest request = request("k-7", Map.of("amount", "10.00"));
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<ResponseEntity<String>> slow = pool.submit(() -> idempotency.execute(request, () -> {
            claimed.countDown();
            await(release);
            return effect();
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> idempotency.execute(request, this::effect))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS));
        } finally {
            release.countDown();
        }
        assertThat(slow.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(effects()).isEqualTo(1);
    }

    private IdempotentRequest request(String key, Object fingerprint) {
        return new IdempotentRequest(clientId, "TEST_OPERATION", key, fingerprint, id -> URI.create("/things/" + id));
    }

    private Outcome effect() {
        executions.incrementAndGet();
        audit.append(new AuditEvent(Actor.system("idempotency-test"), "TEST_EFFECT", "TEST", entity, null, null,
                Map.of()));
        return Outcome.created(Map.of("id", entity), entity);
    }

    private long effects() {
        return privilegedJdbc().sql("SELECT COUNT(*) FROM audit_events WHERE entity_id = ?").param(entity)
                .query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
