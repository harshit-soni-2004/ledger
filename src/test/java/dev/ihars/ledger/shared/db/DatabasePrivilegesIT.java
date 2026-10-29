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
                "GRANT SELECT, INSERT ON `ledger`.`accounts` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT, UPDATE (`balance`, `last_entry_seq`, `updated_at`) ON `ledger`.`account_balances` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT ON `ledger`.`journal_transactions` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT ON `ledger`.`ledger_entries` TO `ledger_app`@`%`",
                "GRANT SELECT, INSERT ON `ledger`.`audit_events` TO `ledger_app`@`%`");
    }

    @Test
    void appUserCannotUpdateOrDeleteAppendOnlyTables() {
        for (String table : List.of("journal_transactions", "ledger_entries", "audit_events")) {
            assertDenied(() -> jdbc.sql("UPDATE " + table + " SET id = id WHERE id = -1").update());
            assertDenied(() -> jdbc.sql("DELETE FROM " + table + " WHERE id = -1").update());
            assertDenied(() -> jdbc.sql("TRUNCATE TABLE " + table).update());
        }
    }

    @Test
    void appUserCannotChangeOrDeleteAccounts() {
        assertDenied(() -> jdbc.sql("UPDATE accounts SET status = 'CLOSED' WHERE id = -1").update());
        assertDenied(() -> jdbc.sql("DELETE FROM accounts WHERE id = -1").update());
        assertDenied(() -> jdbc.sql("DELETE FROM account_balances WHERE account_id = -1").update());
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
