package dev.ihars.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ApiClientsApiIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void anAdminCreatesAClientWhoseTokenIsShownOnceAndStoredOnlyAsAHash() {
        HttpResponse<String> response = postAs(ADMIN_KEY, "/api/v1/api-clients",
                "{\"name\": \"Asha's shop\", \"role\": \"CLIENT\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        JsonNode body = JSON.readTree(response.body());
        String clientId = body.get("id").asString();
        String token = body.get("key").get("token").asString();
        assertThat(clientId).matches("cli_[0-9A-Z]{26}");
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/api-clients/" + clientId);
        assertThat(body.get("role").asString()).isEqualTo("CLIENT");
        assertThat(ApiKeys.isWellFormed(token)).isTrue();
        assertThat(body.get("key").get("prefix").asString()).isEqualTo(ApiKeys.displayPrefix(token));

        assertThat(count("SELECT COUNT(*) FROM api_keys WHERE key_hash = UNHEX(SHA2(?, 256))", token)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE payload LIKE CONCAT('%', ?, '%')", token)).isZero();

        HttpResponse<String> details = getAs(ADMIN_KEY, "/api/v1/api-clients/" + clientId);
        assertThat(details.statusCode()).isEqualTo(200);
        assertThat(details.body()).doesNotContain(token).contains(ApiKeys.displayPrefix(token));
    }

    @Test
    void rotationAllowsTwoActiveKeysThenRequiresARevocation() {
        TestClient client = newClient(ApiRole.CLIENT);
        String keys = "/api/v1/api-clients/" + client.id() + "/keys";

        HttpResponse<String> second = postAs(ADMIN_KEY, keys, null);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(postAs(ADMIN_KEY, keys, null).statusCode()).isEqualTo(409);

        assertThat(postAs(ADMIN_KEY, keys + "/" + client.keyId() + "/revoke", null).statusCode()).isEqualTo(204);
        assertThat(postAs(ADMIN_KEY, keys + "/" + client.keyId() + "/revoke", null).statusCode()).isEqualTo(404);
        assertThat(postAs(ADMIN_KEY, keys, null).statusCode()).isEqualTo(201);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE action = 'API_KEY_REVOKED' AND entity_id = ?",
                client.keyId())).isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThat(postAs(ADMIN_KEY, "/api/v1/api-clients", "{\"name\": \"x\", \"role\": \"ROOT\"}").statusCode())
                .isEqualTo(400);
        assertThat(postAs(ADMIN_KEY, "/api/v1/api-clients", "{\"name\": \" \", \"role\": \"CLIENT\"}").statusCode())
                .isEqualTo(400);
        assertThat(postAs(ADMIN_KEY, "/api/v1/api-clients", "{\"name\": \"x\", \"role\": \"CLIENT\", \"admin\": true}")
                .statusCode()).isEqualTo(400);
        assertThat(getAs(ADMIN_KEY, "/api/v1/api-clients/cli_01JC0000000000000000000000").statusCode())
                .isEqualTo(404);
    }

    @Test
    void anAdminCannotDisableItselfAndDisabledClientsGetNoNewKeys() {
        String self = JSON.readTree(postAs(ADMIN_KEY, "/api/v1/api-clients",
                "{\"name\": \"Second admin\", \"role\": \"ADMIN\"}").body()).get("id").asString();
        String secondAdminKey = JSON.readTree(postAs(ADMIN_KEY, "/api/v1/api-clients/" + self + "/keys", null)
                .body()).get("token").asString();

        assertThat(postAs(secondAdminKey, "/api/v1/api-clients/" + self + "/disable", null).statusCode())
                .isEqualTo(409);
        assertThat(postAs(ADMIN_KEY, "/api/v1/api-clients/" + self + "/disable", null).statusCode()).isEqualTo(200);
        assertThat(postAs(ADMIN_KEY, "/api/v1/api-clients/" + self + "/keys", null).statusCode()).isEqualTo(409);
    }

    @Test
    void operatorsAndClientsCannotManageClients() {
        for (ApiRole role : new ApiRole[] {ApiRole.CLIENT, ApiRole.OPERATOR}) {
            assertThat(postAs(keyFor(role), "/api/v1/api-clients", "{\"name\": \"x\", \"role\": \"ADMIN\"}")
                    .statusCode()).isEqualTo(403);
        }
        assertThat(count("SELECT COUNT(*) FROM api_clients WHERE name = 'x' AND role = 'ADMIN'")).isZero();
    }

    private static long count(String sql, Object... params) {
        var statement = privilegedJdbc().sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }
}
