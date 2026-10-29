package dev.ihars.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.shared.time.BusinessCalendar;
import dev.ihars.ledger.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Phase 4 happy path end to end, over HTTP only, as an operator team would drive it. */
class Phase4WorkflowIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BANK_INR = "acct_01M62F9N00TFYM9JSE7CRQM2PH";

    @Autowired
    private BusinessCalendar calendar;

    @Test
    void onboardFundFreezeUnfreezeCheckAndRevoke() {
        JsonNode client = ok(201, postAs(ADMIN_KEY, "/api/v1/api-clients",
                "{\"name\": \"Asha\", \"role\": \"CLIENT\"}"));
        String clientId = client.get("id").asString();
        String token = client.get("key").get("token").asString();
        JsonNode operator = ok(201, postAs(ADMIN_KEY, "/api/v1/api-clients",
                "{\"name\": \"Ops desk\", \"role\": \"OPERATOR\"}"));
        String operatorToken = operator.get("key").get("token").asString();

        String wallet = ok(201, postAs(token, "/api/v1/accounts",
                "{\"name\": \"Asha INR\", \"type\": \"LIABILITY\", \"currency\": \"INR\"}", idem())).get("id")
                .asString();

        JsonNode opening = ok(201, postAs(ADMIN_KEY, "/api/v1/journal-transactions/openings", """
                {"currency": "INR", "effectiveDate": "%s", "reason": "Balance migrated from legacy core",
                 "lines": [
                   {"accountId": "%s", "direction": "DEBIT", "amount": {"value": "1000.00", "currency": "INR"}},
                   {"accountId": "%s", "direction": "CREDIT", "amount": {"value": "1000.00", "currency": "INR"}}]}
                """.formatted(calendar.today().minusDays(28), BANK_INR, wallet), idem()));

        JsonNode balance = ok(200, getAs(token, "/api/v1/accounts/" + wallet + "/balance"));
        assertThat(balance.get("ledgerBalance").get("value").asString()).isEqualTo("1000.00");
        JsonNode entries = ok(200, getAs(token, "/api/v1/accounts/" + wallet + "/entries"));
        assertThat(entries.get("data").get(0).get("journalId").asString()).isEqualTo(opening.get("id").asString());
        assertThat(entries.get("data").get(0).get("journalType").asString()).isEqualTo("OPENING");
        assertThat(ok(200, getAs(operatorToken, "/api/v1/journal-transactions/" + opening.get("id").asString()))
                .get("entries")).hasSize(2);

        String reason = "{\"reason\": \"Chargeback investigation\"}";
        assertThat(ok(200, postAs(operatorToken, "/api/v1/accounts/" + wallet + "/freeze", reason, idem()))
                .get("status").asString()).isEqualTo("FROZEN");
        assertThat(ok(200, getAs(token, "/api/v1/accounts/" + wallet)).get("status").asString()).isEqualTo("FROZEN");
        assertThat(ok(200, postAs(ADMIN_KEY, "/api/v1/accounts/" + wallet + "/unfreeze", reason, idem()))
                .get("status").asString()).isEqualTo("ACTIVE");

        JsonNode check = ok(200, postAs(ADMIN_KEY, "/api/v1/ops/integrity-checks", null));
        assertThat(check.get("clean").asBoolean()).as(check.toString()).isTrue();

        String keyId = client.get("key").get("id").asString();
        assertThat(postAs(ADMIN_KEY, "/api/v1/api-clients/" + clientId + "/keys/" + keyId + "/revoke", null)
                .statusCode()).isEqualTo(204);
        assertThat(getAs(token, "/api/v1/accounts/" + wallet).statusCode()).isEqualTo(401);
    }

    private static String[] idem() {
        return new String[] {"Idempotency-Key", UUID.randomUUID().toString()};
    }

    private static JsonNode ok(int status, HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
}
