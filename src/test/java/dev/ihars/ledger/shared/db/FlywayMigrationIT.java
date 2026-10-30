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
                .contains("V1__create_currencies.sql", "V2__create_accounts_and_balances.sql",
                        "V3__create_journals_and_entries.sql", "V4__create_audit_events.sql",
                        "V5__seed_system_accounts.sql", "V6__create_api_clients_and_account_ownership.sql",
                        "V7__create_idempotency_records.sql", "V8__create_integrity_check_runs.sql",
                        "V9__create_transfers.sql", "R__runtime_grants.sql");
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
    void everyCurrencyHasTheSystemAccountsWithABalanceRowAndNoFloor() {
        // Balances are not asserted: other test classes post against the system accounts.
        var rows = jdbc.sql("""
                        SELECT a.currency, a.system_code, a.type, a.normal_balance, a.overdraft_policy,
                               b.min_balance
                          FROM accounts a JOIN account_balances b ON b.account_id = a.id
                         WHERE a.system_code IS NOT NULL
                        """)
                .query((rs, n) -> {
                    assertThat(rs.getString("overdraft_policy")).isEqualTo("UNLIMITED");
                    assertThat(rs.getBigDecimal("min_balance")).isNull();
                    return rs.getString("currency") + ":" + rs.getString("system_code") + ":"
                            + rs.getString("type") + ":" + rs.getString("normal_balance");
                })
                .list();

        assertThat(rows).hasSize(25);
        for (String currency : new String[] {"EUR", "INR", "JPY", "KWD", "USD"}) {
            assertThat(rows).contains(
                    currency + ":ASSET.BANK.OPERATING:ASSET:DEBIT",
                    currency + ":LIABILITY.SUSPENSE:LIABILITY:CREDIT",
                    currency + ":EQUITY.OWNER_CAPITAL:EQUITY:CREDIT",
                    currency + ":REVENUE.FEES:REVENUE:CREDIT",
                    currency + ":EXPENSE.BANK_CHARGES:EXPENSE:DEBIT");
        }
    }

    @Test
    void ledgerTablesHaveAppendOnlyTriggers() {
        var triggers = privilegedJdbc()
                .sql("""
                        SELECT CONCAT(event_object_table, ':', action_timing, ' ', event_manipulation)
                          FROM information_schema.triggers WHERE trigger_schema = 'ledger'
                        """)
                .query(String.class)
                .list();

        assertThat(triggers).containsExactlyInAnyOrder(
                "journal_transactions:BEFORE UPDATE", "journal_transactions:BEFORE DELETE",
                "ledger_entries:BEFORE UPDATE", "ledger_entries:BEFORE DELETE",
                "audit_events:BEFORE UPDATE", "audit_events:BEFORE DELETE",
                "integrity_check_runs:BEFORE UPDATE", "integrity_check_runs:BEFORE DELETE",
                "transfers:BEFORE DELETE");
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
