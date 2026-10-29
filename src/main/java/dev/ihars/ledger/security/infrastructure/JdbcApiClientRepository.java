package dev.ihars.ledger.security.infrastructure;

import dev.ihars.ledger.security.ApiClient;
import dev.ihars.ledger.security.ApiClientPrincipal;
import dev.ihars.ledger.security.ApiKey;
import dev.ihars.ledger.security.ApiRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApiClientRepository {

    private static final String CLIENT_COLUMNS = "c.id, c.public_id, c.name, c.role, c.status, c.created_at";
    private static final String KEY_COLUMNS = """
            k.public_id, c.public_id AS client_public_id, k.key_prefix, k.status, k.created_at, k.revoked_at
            """;

    private final JdbcClient jdbc;

    public JdbcApiClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The caller behind an active key of an active client, or empty. One indexed lookup. */
    public Optional<ApiClientPrincipal> findPrincipal(byte[] keyHash, Instant now) {
        return jdbc.sql("""
                SELECT c.id, c.public_id AS client_public_id, c.role, k.public_id AS key_public_id
                  FROM api_keys k
                  JOIN api_clients c ON c.id = k.client_id
                 WHERE k.key_hash = :hash
                   AND k.status = 'ACTIVE'
                   AND c.status = 'ACTIVE'
                   AND (k.expires_at IS NULL OR k.expires_at > :now)
                """)
                .param("hash", keyHash)
                .param("now", utc(now))
                .query((rs, n) -> new ApiClientPrincipal(rs.getLong("id"), rs.getString("client_public_id"),
                        ApiRole.valueOf(rs.getString("role")), rs.getString("key_public_id")))
                .optional();
    }

    public boolean keyHashExists(byte[] keyHash) {
        return jdbc.sql("SELECT COUNT(*) FROM api_keys WHERE key_hash = :hash")
                .param("hash", keyHash).query(Long.class).single() > 0;
    }

    public boolean activeAdminExists() {
        return jdbc.sql("SELECT COUNT(*) FROM api_clients WHERE role = 'ADMIN' AND status = 'ACTIVE'")
                .query(Long.class).single() > 0;
    }

    public ApiClient insertClient(String publicId, String name, ApiRole role, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO api_clients (public_id, name, role, status, created_at, updated_at)
                VALUES (:publicId, :name, :role, 'ACTIVE', :now, :now)
                """)
                .param("publicId", publicId)
                .param("name", name)
                .param("role", role.name())
                .param("now", utc(now))
                .update(keys, "id");
        return new ApiClient(keys.getKeyAs(Number.class).longValue(), publicId, name, role, ApiClient.Status.ACTIVE,
                now);
    }

    public ApiKey insertKey(String publicId, ApiClient client, String prefix, byte[] keyHash, Instant now) {
        jdbc.sql("""
                INSERT INTO api_keys (public_id, client_id, key_prefix, key_hash, status, created_at)
                VALUES (:publicId, :clientId, :prefix, :hash, 'ACTIVE', :now)
                """)
                .param("publicId", publicId)
                .param("clientId", client.id())
                .param("prefix", prefix)
                .param("hash", keyHash)
                .param("now", utc(now))
                .update();
        return new ApiKey(publicId, client.publicId(), prefix, ApiKey.Status.ACTIVE, now, null);
    }

    public Optional<ApiClient> findClient(String publicId) {
        return jdbc.sql("SELECT " + CLIENT_COLUMNS + " FROM api_clients c WHERE c.public_id = :publicId")
                .param("publicId", publicId)
                .query((rs, n) -> client(rs))
                .optional();
    }

    public Optional<ApiClient> findClientById(long id) {
        return jdbc.sql("SELECT " + CLIENT_COLUMNS + " FROM api_clients c WHERE c.id = :id")
                .param("id", id)
                .query((rs, n) -> client(rs))
                .optional();
    }

    /** Serializes key issuance, revocation and disabling per client. */
    public Optional<ApiClient> lockClient(String publicId) {
        return jdbc.sql("SELECT " + CLIENT_COLUMNS + " FROM api_clients c WHERE c.public_id = :publicId FOR UPDATE")
                .param("publicId", publicId)
                .query((rs, n) -> client(rs))
                .optional();
    }

    public List<ApiKey> keys(long clientId) {
        return jdbc.sql("SELECT " + KEY_COLUMNS + """
                  FROM api_keys k JOIN api_clients c ON c.id = k.client_id
                 WHERE k.client_id = :clientId
                 ORDER BY k.id
                """)
                .param("clientId", clientId)
                .query((rs, n) -> key(rs))
                .list();
    }

    public int revokeKey(long clientId, String keyPublicId, Instant now) {
        return jdbc.sql("""
                UPDATE api_keys SET status = 'REVOKED', revoked_at = :now
                 WHERE client_id = :clientId AND public_id = :publicId AND status = 'ACTIVE'
                """)
                .param("now", utc(now))
                .param("clientId", clientId)
                .param("publicId", keyPublicId)
                .update();
    }

    public void disableClient(long clientId, Instant now) {
        jdbc.sql("UPDATE api_clients SET status = 'DISABLED', updated_at = :now WHERE id = :id")
                .param("now", utc(now))
                .param("id", clientId)
                .update();
    }

    private static ApiClient client(ResultSet rs) throws SQLException {
        return new ApiClient(rs.getLong("id"), rs.getString("public_id"), rs.getString("name"),
                ApiRole.valueOf(rs.getString("role")), ApiClient.Status.valueOf(rs.getString("status")),
                instant(rs, "created_at"));
    }

    private static ApiKey key(ResultSet rs) throws SQLException {
        return new ApiKey(rs.getString("public_id"), rs.getString("client_public_id"), rs.getString("key_prefix"),
                ApiKey.Status.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
