package dev.ihars.ledger.idempotency.infrastructure;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRepository {

    private final JdbcClient jdbc;

    public JdbcIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key. Blocks while another transaction holds an uncommitted claim on the same
     * scope; fails with a duplicate-key error once that claim is committed.
     */
    public long insert(long clientId, String operation, String key, byte[] requestHash, Instant now,
            Instant expiresAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO idempotency_records
                       (client_id, operation, idempotency_key, request_hash, created_at, expires_at)
                VALUES (:clientId, :operation, :key, :hash, :now, :expiresAt)
                """)
                .param("clientId", clientId)
                .param("operation", operation)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", utc(now))
                .param("expiresAt", utc(expiresAt))
                .update(keys, "id");
        return keys.getKeyAs(Number.class).longValue();
    }

    public void complete(long id, int status, String body, String resourceId) {
        jdbc.sql("""
                UPDATE idempotency_records
                   SET response_status = :status, response_body = :body, resource_id = :resourceId
                 WHERE id = :id
                """)
                .param("status", status)
                .param("body", body)
                .param("resourceId", resourceId)
                .param("id", id)
                .update();
    }

    public Optional<StoredRecord> find(long clientId, String operation, String key) {
        return jdbc.sql("""
                SELECT request_hash, response_status, response_body, resource_id
                  FROM idempotency_records
                 WHERE client_id = :clientId AND operation = :operation AND idempotency_key = :key
                """)
                .param("clientId", clientId)
                .param("operation", operation)
                .param("key", key)
                .query((rs, n) -> new StoredRecord(rs.getBytes("request_hash"),
                        rs.getObject("response_status", Integer.class), rs.getString("response_body"),
                        rs.getString("resource_id")))
                .optional();
    }

    public record StoredRecord(byte[] requestHash, Integer status, String body, String resourceId) {}

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
