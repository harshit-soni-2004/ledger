package dev.ihars.ledger.ops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IntegrityChecksApiIT extends LedgerIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CHECKS = "/api/v1/ops/integrity-checks";

    @Autowired
    private ApplicationContext context;

    @Test
    void anAdminRunsTheChecksAndTheResultIsRecorded() {
        data.deposit(data.wallet("INR"), "4.00");

        HttpResponse<String> response = postAs(ADMIN_KEY, CHECKS, null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode run = JSON.readTree(response.body());
        String id = run.get("id").asString();
        assertThat(id).startsWith("ichk_");
        assertThat(run.get("trigger").asString()).isEqualTo("MANUAL");
        assertThat(run.get("clean").asBoolean()).isTrue();
        assertThat(run.get("violationCount").asInt()).isZero();
        assertThat(run.get("violations")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM integrity_check_runs WHERE public_id = ? AND violation_count = 0", id))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id = ? AND action = ?", id,
                IntegrityCheckService.INTEGRITY_CHECK_COMPLETED)).isEqualTo(1);

        JsonNode latest = JSON.readTree(getAs(ADMIN_KEY, CHECKS + "/latest").body());
        assertThat(latest.get("id").asString()).isEqualTo(id);
    }

    @Test
    void onlyAdminsMayRunOrReadChecks() {
        for (ApiRole role : new ApiRole[] {ApiRole.CLIENT, ApiRole.OPERATOR}) {
            assertThat(postAs(keyFor(role), CHECKS, null).statusCode()).isEqualTo(403);
            assertThat(getAs(keyFor(role), CHECKS + "/latest").statusCode()).isEqualTo(403);
        }
        assertThat(postAs(null, CHECKS, null).statusCode()).isEqualTo(401);
    }

    @Test
    void theScheduleIsOffInTests() {
        assertThat(context.getBeanNamesForType(ScheduledIntegrityCheck.class)).isEmpty();
    }
}
