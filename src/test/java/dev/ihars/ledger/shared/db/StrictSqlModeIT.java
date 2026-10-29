package dev.ihars.ledger.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.support.IntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Strict SQL mode makes MySQL reject invalid data instead of silently converting or
 * truncating it (invariant I10, docs/05-invariants.md).
 */
class StrictSqlModeIT extends IntegrationTest {

    private static final int INCORRECT_VALUE = 1366;
    private static final int DATA_TOO_LONG = 1406;
    private static final int OUT_OF_RANGE = 1264;

    @Autowired
    JdbcClient jdbc;

    @Test
    void applicationSessionsRunInStrictMode() {
        assertThat(appSqlMode().split(","))
                .contains("STRICT_TRANS_TABLES", "NO_ZERO_DATE", "NO_ZERO_IN_DATE",
                        "ERROR_FOR_DIVISION_BY_ZERO", "ONLY_FULL_GROUP_BY");
    }

    @Test
    void serverDefaultIsAlsoStrict() {
        String globalMode = privilegedJdbc().sql("SELECT @@GLOBAL.sql_mode").query(String.class).single();

        assertThat(globalMode.split(",")).contains("STRICT_TRANS_TABLES");
    }

    @Test
    void invalidValuesAreRejectedUnderTheApplicationSqlMode() throws SQLException {
        // The app user cannot create tables, so probe as root using the app's session mode.
        String appMode = appSqlMode();
        try (Connection connection = privilegedDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION sql_mode = '" + appMode + "'");
            statement.execute("CREATE TEMPORARY TABLE strict_probe (amount DECIMAL(19,4), code CHAR(3))");

            assertRejected(statement, "INSERT INTO strict_probe VALUES ('abc', 'INR')", INCORRECT_VALUE);
            assertRejected(statement, "INSERT INTO strict_probe VALUES (1, 'ABCD')", DATA_TOO_LONG);
            assertRejected(statement, "INSERT INTO strict_probe VALUES (1000000000000000, 'INR')", OUT_OF_RANGE);
        }
    }

    private String appSqlMode() {
        return jdbc.sql("SELECT @@SESSION.sql_mode").query(String.class).single();
    }

    private static void assertRejected(Statement statement, String sql, int expectedErrorCode) {
        assertThatThrownBy(() -> statement.execute(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(expectedErrorCode));
    }
}
