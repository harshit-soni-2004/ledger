package dev.ihars.ledger.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.support.IntegrationTest;
import java.util.Arrays;
import java.util.Currency;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

class FlywayMigrationIT extends IntegrationTest {

    private static final int CHECK_CONSTRAINT_VIOLATED = 3819;

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcClient jdbc;

    @Test
    void allMigrationsAppliedSuccessfullyAndValidate() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(Arrays.stream(applied).map(MigrationInfo::getScript))
                .contains("V1__create_currencies.sql", "R__runtime_grants.sql");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void migrationsRanAsMigrationUser() {
        var installers = privilegedJdbc()
                .sql("SELECT DISTINCT installed_by FROM flyway_schema_history")
                .query(String.class)
                .list();

        assertThat(installers).containsExactly(MIGRATION_USER);
    }

    @Test
    void rerunningMigrateIsANoOp() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void seededCurrenciesMatchIso4217MinorUnits() {
        Map<String, Integer> seeded = jdbc.sql("SELECT code, minor_units FROM currencies")
                .query((rs, n) -> Map.entry(rs.getString("code"), rs.getInt("minor_units")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(seeded).containsOnlyKeys("INR", "USD", "EUR", "JPY", "KWD");
        assertThat(seeded).containsEntry("JPY", 0).containsEntry("KWD", 3).containsEntry("INR", 2);
        seeded.forEach((code, minorUnits) -> assertThat(minorUnits)
                .as("minor units of %s", code)
                .isEqualTo(Currency.getInstance(code).getDefaultFractionDigits()));
    }

    @Test
    void currencyMinorUnitsOutsideZeroToFourAreRejected() {
        assertThatThrownBy(() -> privilegedJdbc()
                        .sql("INSERT INTO currencies (code, minor_units) VALUES ('XAA', 5)")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(CHECK_CONSTRAINT_VIOLATED));
    }

    @Test
    void currencyCodesMustBeThreeUppercaseLetters() {
        assertThatThrownBy(() -> privilegedJdbc()
                        .sql("INSERT INTO currencies (code, minor_units) VALUES ('xa1', 2)")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(CHECK_CONSTRAINT_VIOLATED));
    }
}
