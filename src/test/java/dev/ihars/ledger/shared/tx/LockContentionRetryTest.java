package dev.ihars.ledger.shared.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

class LockContentionRetryTest {

    private final LockContentionRetry retry = new LockContentionRetry();

    @Test
    void retriesLockFailuresUntilAnAttemptSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.run("TEST", () -> {
            if (attempts.incrementAndGet() < LockContentionRetry.MAX_ATTEMPTS) {
                throw new CannotAcquireLockException("deadlock");
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(attempts).hasValue(LockContentionRetry.MAX_ATTEMPTS);
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.run("TEST", () -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("lock wait timeout");
        })).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(attempts).hasValue(LockContentionRetry.MAX_ATTEMPTS);
    }

    @Test
    void otherFailuresAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.run("TEST", () -> {
            attempts.incrementAndGet();
            throw new DataIntegrityViolationException("duplicate");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void backoffGrowsAndStaysWithinTheJitterBand() {
        for (int i = 0; i < 100; i++) {
            assertThat(LockContentionRetry.backoff(1)).isBetween(10L, 30L);
            assertThat(LockContentionRetry.backoff(2)).isBetween(20L, 60L);
        }
    }
}
