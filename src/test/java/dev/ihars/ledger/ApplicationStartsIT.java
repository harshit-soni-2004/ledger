package dev.ihars.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.jdbc.core.simple.JdbcClient;

class ApplicationStartsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HealthEndpointGroups healthGroups;

    @Test
    void livenessAndReadinessAreUp() {
        HttpResponse<String> liveness = get("/actuator/health/liveness");
        HttpResponse<String> readiness = get("/actuator/health/readiness");

        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessDependsOnTheDatabaseButLivenessDoesNot() {
        // A database outage must take the instance out of rotation without restarting it.
        assertThat(healthGroups.get("readiness").isMember("db")).isTrue();
        assertThat(healthGroups.get("liveness").isMember("db")).isFalse();
    }

    @Test
    void applicationConnectsAsLeastPrivilegeUser() {
        String user = jdbc.sql("SELECT CURRENT_USER()").query(String.class).single();

        assertThat(user).isEqualTo(APP_USER + "@%");
    }

    @Test
    void applicationSessionsUseReadCommittedIsolation() {
        String isolation = jdbc.sql("SELECT @@SESSION.transaction_isolation").query(String.class).single();

        assertThat(isolation).isEqualTo("READ-COMMITTED");
    }

    @Test
    void applicationSessionsUseUtc() {
        String timeZone = jdbc.sql("SELECT @@SESSION.time_zone").query(String.class).single();

        assertThat(timeZone).isIn("UTC", "+00:00");
    }

    @Test
    void openApiDocsAreDisabledOutsideLocalProfile() {
        assertThat(get("/v3/api-docs").statusCode()).isEqualTo(404);
    }
}
