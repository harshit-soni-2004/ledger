package dev.ihars.ledger.shared.tx;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Re-runs a whole transaction after a deadlock (MySQL 1213) or lock-wait timeout (1205):
 * bounded attempts with jittered exponential backoff (docs/06-concurrency-and-failure-modes.md).
 *
 * <p>Must wrap the transaction from the outside. Inside a transaction the failed attempt has
 * already been rolled back by MySQL, and continuing in it is meaningless, so calling this with
 * a transaction active is a programming error.
 */
@Component
public class LockContentionRetry {

    private static final Logger log = LoggerFactory.getLogger(LockContentionRetry.class);

    public static final int MAX_ATTEMPTS = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(20);

    public <T> T run(String operation, Supplier<T> transaction) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Lock retries must wrap the transaction, not run inside it");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("Lock contention on {}: giving up after {} attempts", operation, attempt);
                    throw e;
                }
                log.warn("Lock contention on {} (attempt {} of {}): {}", operation, attempt, MAX_ATTEMPTS,
                        e.getClass().getSimpleName());
                sleep(backoff(attempt));
            }
        }
    }

    /** 20 ms, 40 ms, ... each with +/-50 % jitter so competing retries spread out. */
    static long backoff(int attempt) {
        long base = BASE_BACKOFF.toMillis() << (attempt - 1);
        return base / 2 + ThreadLocalRandom.current().nextLong(base + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }
}
