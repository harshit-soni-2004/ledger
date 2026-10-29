package dev.ihars.ledger.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The runtime user holds only explicitly granted table privileges; DDL and migration history
 * belong to the migration user (docs/adr/0008-immutability-and-reversals.md).
 */
class DatabasePrivilegesIT extends IntegrationTest {

    private static final int TABLE_ACCESS_DENIED = 1142;
    private static final int COLUMN_ACCESS_DENIED = 1143;

    @Autowired
    JdbcClient jdbc;

    @Test
    void appUserHoldsOnlyExplicitTableGrants() {
        List<String> grants = jdbc.sql("SHOW GRANTS").query(String.class).list();

        assertThat(grants).containsExactlyInAnyOrder(
                "GRANT USAGE ON *.* TO `ledger_app`@`%`",
                "GRANT SELECT ON `ledger`.`currencies` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT, UPDATE (`status`, `updated_at`) ON `ledger`.`accounts` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT, UPDATE (`balance`, `last_entry_seq`, `updated_at`) ON `ledger`.`account_balances` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT ON `ledger`.`journal_transactions` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT ON `ledger`.`ledger_entries` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT ON `ledger`.`audit_events` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT, UPDATE (`status`, `updated_at`) ON `ledger`.`api_clients` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT, UPDATE (`revoked_at`, `status`) ON `ledger`.`api_keys` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT, UPDATE (`resource_id`, `response_body`, `response_status`) ON `ledger`.`idempotency_records` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT ON `ledger`.`integrity_check_runs` TO `ledger_app`@`%`");
    }

    @Test
    void appUserCannotUpdateOrDeleteAppendOnlyTables() {
        for (String table : List.of("journal_transactions", "ledger_entries", "audit_events", "integrity_check_runs")) {
            assertDenied(() -> jdbc.sql("UPDATE " + table + " SET id = id WHERE id = -1").update());
            assertDenied(() -> jdbc.sql("DELETE FROM " + table + " WHERE id = -1").update());
            assertDenied(() -> jdbc.sql("TRUNCATE TABLE " + table).update());
        }
    }

    @Test
    void appUserCannotDeleteAccountsClientsKeysOrIdempotencyRecords() {
        for (String table : List.of("accounts", "account_balances", "api_clients", "api_keys", "idempotency_records")) {
            assertDenied(() -> jdbc.sql("DELETE FROM " + table + " WHERE 1 = 0").update());
            assertDenied(() -> jdbc.sql("TRUNCATE TABLE " + table).update());
        }
    }

    @Test
    void appUserMayChangeOnlyTheLifecycleColumnsOfAccountsClientsAndKeys() {
        assertThat(jdbc.sql("UPDATE accounts SET status = status, updated_at = updated_at WHERE id = -1").update())
                .isZero();
        assertThat(jdbc.sql("UPDATE api_clients SET status = status, updated_at = updated_at WHERE id = -1").update())
                .isZero();
        assertThat(jdbc.sql("UPDATE api_keys SET status = status, revoked_at = revoked_at WHERE id = -1").update())
                .isZero();

        for (String column : List.of("name", "type", "normal_balance", "currency", "overdraft_policy",
                "overdraft_limit", "owner_client_id", "system_code", "public_id")) {
            assertColumnDenied("UPDATE accounts SET " + column + " = " + column + " WHERE id = -1");
        }
        for (String column : List.of("role", "name", "public_id")) {
            assertColumnDenied("UPDATE api_clients SET " + column + " = " + column + " WHERE id = -1");
        }
        for (String column : List.of("key_hash", "client_id", "expires_at", "key_prefix")) {
            assertColumnDenied("UPDATE api_keys SET " + column + " = " + column + " WHERE id = -1");
        }
        for (String column : List.of("request_hash", "idempotency_key", "client_id", "operation", "expires_at")) {
            assertColumnDenied("UPDATE idempotency_records SET " + column + " = " + column + " WHERE id = -1");
        }
    }

    private void assertColumnDenied(String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).update())
                .as(sql)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(COLUMN_ACCESS_DENIED));
    }

    @Test
    void appUserMayUpdateOnlyTheBalanceProjectionColumns() {
        assertThat(jdbc.sql("UPDATE account_balances SET balance = balance, last_entry_seq = last_entry_seq, "
                + "updated_at = updated_at WHERE account_id = -1").update()).isZero();

        for (String column : List.of("min_balance", "currency", "account_id")) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE account_balances SET " + column + " = " + column
                            + " WHERE account_id = -1").update())
                    .isInstanceOf(DataAccessException.class)
                    .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(COLUMN_ACCESS_DENIED));
        }
    }

    @Test
    @Transactional
    void columnLevelUpdateGrantIsEnoughForLockingReads() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account_balances WHERE account_id = -1 FOR UPDATE")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void appUserCanReadReferenceData() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM currencies").query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    void appUserCannotModifyReferenceData() {
        assertDenied(() -> jdbc.sql("INSERT INTO currencies (code, minor_units) VALUES ('XAA', 2)").update());
        assertDenied(() -> jdbc.sql("UPDATE currencies SET minor_units = 4 WHERE code = 'INR'").update());
        assertDenied(() -> jdbc.sql("DELETE FROM currencies WHERE code = 'INR'").update());
    }

    @Test
    void appUserCannotRunDdl() {
        assertDenied(() -> jdbc.sql("CREATE TABLE should_not_exist (id INT)").update());
        assertDenied(() -> jdbc.sql("DROP TABLE currencies").update());
    }

    @Test
    void appUserCannotReadMigrationHistory() {
        assertDenied(() -> jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history").query(Integer.class).single());
    }

    @Test
    void migrationUserOwnsTheSchemaOnly() {
        List<String> grants = privilegedJdbc()
                .sql("SHOW GRANTS FOR '" + MIGRATION_USER + "'@'%'")
                .query(String.class)
                .list();

        assertThat(grants).containsExactlyInAnyOrder(
                "GRANT USAGE ON *.* TO `ledger_migrator`@`%`",
                "GRANT ALL PRIVILEGES ON `ledger`.* TO `ledger_migrator`@`%` WITH GRANT OPTION");
    }

    @Test
    void onlyTheExpectedAccountsExist() {
        List<String> accounts = privilegedJdbc()
                .sql("""
                        SELECT CONCAT(user, '@', host) FROM mysql.user
                        WHERE user NOT IN ('root', 'mysql.sys', 'mysql.session', 'mysql.infoschema')
                        """)
                .query(String.class)
                .list();

        assertThat(accounts).containsExactlyInAnyOrder(MIGRATION_USER + "@%", APP_USER + "@%");
    }

    private static void assertDenied(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(TABLE_ACCESS_DENIED));
    }
}
