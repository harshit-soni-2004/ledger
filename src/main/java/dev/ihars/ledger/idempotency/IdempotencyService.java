package dev.ihars.ledger.idempotency;

import dev.ihars.ledger.idempotency.infrastructure.JdbcIdempotencyRepository;
import dev.ihars.ledger.idempotency.infrastructure.JdbcIdempotencyRepository.StoredRecord;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exactly-once effects for retried mutating requests (ADR-0006).
 *
 * <p>The key is claimed, the operation runs and its response is stored in <em>one</em>
 * transaction, so a committed key always has an outcome and a failed operation leaves no key
 * behind (the client may retry with the same key). Only 2xx responses are stored; business
 * rejections and errors roll back.
 *
 * <p>Concurrency is resolved by the unique scope {@code (client, operation, key)}: a duplicate
 * blocks on the insert until the first transaction ends. If it committed, the duplicate replays
 * the stored response; if it rolled back, the duplicate proceeds as the first attempt. A
 * duplicate that waits longer than the session lock-wait timeout gets {@code 409
 * REQUEST_IN_PROGRESS}.
 *
 * <p>Records are not purged yet (no DELETE grant), so an expired key keeps replaying; that is the
 * conservative behaviour.
 */
@Service
public class IdempotencyService {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    public static final Duration RETENTION = Duration.ofHours(24);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final JdbcIdempotencyRepository records;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotencyService(JdbcIdempotencyRepository records, PlatformTransactionManager transactions,
            JsonMapper json, Clock clock) {
        this.records = records;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
        this.clock = clock;
    }

    /**
     * Runs {@code action} at most once per key and returns its response, or replays the stored
     * response of an earlier successful attempt with {@code Idempotent-Replayed: true}.
     *
     * @throws ApiProblemException for a missing, malformed or reused key, or a request in progress;
     *     exceptions from {@code action} propagate unchanged after the rollback
     */
    public ResponseEntity<String> execute(IdempotentRequest request, Supplier<Outcome> action) {
        String key = validKey(request.key());
        byte[] hash = fingerprint(request);
        try {
            StoredRecord stored = tx.execute(status -> {
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                long id = claim(request, key, hash, now);
                Outcome outcome = action.get();
                String body = outcome.body() == null ? null : json.writeValueAsString(outcome.body());
                records.complete(id, outcome.status(), body, outcome.resourceId());
                return new StoredRecord(hash, outcome.status(), body, outcome.resourceId());
            });
            return respond(request, stored, false);
        } catch (AlreadyClaimed e) {
            StoredRecord existing = records.find(request.clientId(), request.operation(), key)
                    .orElseThrow(IdempotencyService::inProgress);
            if (!MessageDigest.isEqual(existing.requestHash(), hash)) {
                throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different request.");
            }
            if (existing.status() == null) {
                throw inProgress();
            }
            return respond(request, existing, true);
        }
    }

    private long claim(IdempotentRequest request, String key, byte[] hash, Instant now) {
        try {
            return records.insert(request.clientId(), request.operation(), key, hash, now, now.plus(RETENTION));
        } catch (DuplicateKeyException e) {
            throw new AlreadyClaimed();
        } catch (PessimisticLockingFailureException e) {
            throw inProgress();
        }
    }

    private static ResponseEntity<String> respond(IdempotentRequest request, StoredRecord stored, boolean replayed) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(stored.status());
        if (request.location() != null && stored.resourceId() != null) {
            response.location(request.location().apply(stored.resourceId()));
        }
        if (replayed) {
            response.header(REPLAYED_HEADER, "true");
        }
        if (stored.body() == null) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_JSON).body(stored.body());
    }

    private byte[] fingerprint(IdempotentRequest request) {
        String canonical = request.operation() + "\n" + json.writeValueAsString(request.fingerprint());
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static String validKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.IDEMPOTENCY_KEY_MISSING,
                    "The Idempotency-Key header is required for this operation.");
        }
        if (!KEY.matcher(key).matches()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key must be 1-128 characters of A-Z, a-z, 0-9, '_' or '-'.");
        }
        return key;
    }

    private static ApiProblemException inProgress() {
        return new ApiProblemException(HttpStatus.CONFLICT, ErrorCode.REQUEST_IN_PROGRESS,
                "A request with this Idempotency-Key is still being processed; retry later.");
    }

    /** The scope is held by a committed record; rolls back the claiming transaction. */
    private static final class AlreadyClaimed extends RuntimeException {
        AlreadyClaimed() {
            super(null, null, false, false);
        }
    }
}
