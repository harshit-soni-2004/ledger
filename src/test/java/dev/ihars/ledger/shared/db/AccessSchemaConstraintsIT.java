package dev.ihars.ledger.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.support.IntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Database-enforced rules for clients, keys, ownership and integrity-check runs. Every probe
 * runs as root in a rolled-back transaction, so constraints (not grants) are what reject it.
 */
class AccessSchemaConstraintsIT extends IntegrationTest {

    private static final int CHECK_VIOLATED = 3819;
    private static final int DUPLICATE_KEY = 1062;
    private static final int SIGNALED_ERROR = 1644;
    private static final String CLIENT_ID = "cli_01JC0000000000000000000000";
    private static final String KEY_ID = "key_01JC0000000000000000000000";
    private static final String SYSTEM_ACCOUNT = "acct_01M62F9N00C9HZ9W4W6YDAFSZF";

    @Test
    void systemAccountsCannotBeFrozenClosedOrOwned() throws SQLException {
        inRolledBackTransaction(root -> {
            long client = insertClient(root, CLIENT_ID, "CLIENT");
            assertRejected(CHECK_VIOLATED, () -> root.sql("UPDATE accounts SET status = 'FROZEN' WHERE public_id = ?")
                    .param(SYSTEM_ACCOUNT).update());
            assertRejected(CHECK_VIOLATED, () -> root.sql("UPDATE accounts SET status = 'CLOSED' WHERE public_id = ?")
                    .param(SYSTEM_ACCOUNT).update());
            assertRejected(CHECK_VIOLATED, () -> root.sql("UPDATE accounts SET owner_client_id = ? WHERE public_id = ?")
                    .param(client).param(SYSTEM_ACCOUNT).update());
        });
    }

    @Test
    void clientAndKeyIdentifiersAndRolesAreConstrained() throws SQLException {
        inRolledBackTransaction(root -> {
            assertRejected(CHECK_VIOLATED, () -> insertClient(root, "cli_lowercase", "CLIENT"));
            assertRejected(DataAccessException.class, () -> insertClient(root, CLIENT_ID, "ROOT"));
            long client = insertClient(root, CLIENT_ID, "OPERATOR");
            assertRejected(DUPLICATE_KEY, () -> insertClient(root, CLIENT_ID, "OPERATOR"));

            insertKey(root, KEY_ID, client, "ACTIVE", null, 1);
            assertRejected(DUPLICATE_KEY, () -> insertKey(root, "key_01JC0000000000000000000001", client, "ACTIVE",
                    null, 1));
            assertRejected(CHECK_VIOLATED, () -> insertKey(root, "key_01JC0000000000000000000002", client, "REVOKED",
                    null, 2));
            assertRejected(CHECK_VIOLATED, () -> insertKey(root, "key_01JC0000000000000000000003", client, "ACTIVE",
                    "2026-10-29 00:00:00", 3));
            assertRejected(CHECK_VIOLATED, () -> insertKey(root, "acct_01JC000000000000000000004", client, "ACTIVE",
                    null, 4));
        });
    }

    @Test
    void idempotencyScopeIsUniquePerClientOperationAndKey() throws SQLException {
        inRolledBackTransaction(root -> {
            long client = insertClient(root, CLIENT_ID, "CLIENT");
            insertIdempotency(root, client, "OPEN_ACCOUNT", "key-1");
            insertIdempotency(root, client, "FREEZE_ACCOUNT", "key-1");
            assertRejected(DUPLICATE_KEY, () -> insertIdempotency(root, client, "OPEN_ACCOUNT", "key-1"));
            assertRejected(CHECK_VIOLATED, () -> insertIdempotency(root, client, "OPEN_ACCOUNT", "bad key!"));
        });
    }

    @Test
    void integrityCheckRunsAreAppendOnly() throws SQLException {
        inRolledBackTransaction(root -> {
            root.sql("""
                    INSERT INTO integrity_check_runs (public_id, trigger_type, triggered_by, started_at, finished_at,
                            violation_count, violations)
                    VALUES ('ichk_01JC0000000000000000000000', 'MANUAL', 'test', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                            0, JSON_ARRAY())
                    """).update();
            assertRejected(SIGNALED_ERROR, () -> root.sql("UPDATE integrity_check_runs SET violation_count = 1")
                    .update());
            assertRejected(SIGNALED_ERROR, () -> root.sql("DELETE FROM integrity_check_runs").update());
        });
    }

    private static long insertClient(JdbcClient root, String publicId, String role) {
        root.sql("""
                INSERT INTO api_clients (public_id, name, role, status, created_at, updated_at)
                VALUES (?, 'probe', ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """).param(publicId).param(role).update();
        return root.sql("SELECT id FROM api_clients WHERE public_id = ?").param(publicId).query(Long.class).single();
    }

    private static void insertKey(JdbcClient root, String publicId, long clientId, String status, String revokedAt,
            int hashSeed) {
        root.sql("""
                INSERT INTO api_keys (public_id, client_id, key_prefix, key_hash, status, created_at, revoked_at)
                VALUES (?, ?, 'lk_probe', UNHEX(SHA2(?, 256)), ?, UTC_TIMESTAMP(6), ?)
                """).param(publicId).param(clientId).param(hashSeed == 1 ? "same" : "seed-" + hashSeed)
                .param(status).param(revokedAt).update();
    }

    private static void insertIdempotency(JdbcClient root, long clientId, String operation, String key) {
        root.sql("""
                INSERT INTO idempotency_records (client_id, operation, idempotency_key, request_hash, created_at,
                        expires_at)
                VALUES (?, ?, ?, UNHEX(SHA2('r', 256)), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """).param(clientId).param(operation).param(key).update();
    }

    private static void assertRejected(int errorCode, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(errorCode));
    }

    private static void assertRejected(Class<? extends Throwable> type, Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(type);
    }

    private static void inRolledBackTransaction(Consumer<JdbcClient> probe) throws SQLException {
        try (Connection connection = privilegedDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                probe.accept(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
            } finally {
                connection.rollback();
            }
        }
    }
}
