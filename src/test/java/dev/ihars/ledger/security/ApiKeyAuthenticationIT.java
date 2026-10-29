package dev.ihars.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every credential failure looks the same to the caller, and no token reaches the logs. */
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyAuthenticationIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROTECTED = "/api/v1/api-clients/cli_01JC0000000000000000000000";

    @Test
    void theTestAdminKeyIsWellFormed() {
        assertThat(ApiKeys.isWellFormed(ADMIN_KEY)).isTrue();
    }

    @Test
    void missingWrongSchemeMalformedAndUnknownCredentialsAreAllUnauthenticated() {
        assertUnauthenticated(get(PROTECTED));
        assertUnauthenticated(get(PROTECTED, "Authorization", "Basic YWRtaW46YWRtaW4="));
        assertUnauthenticated(get(PROTECTED, "Authorization", "Bearer"));
        assertUnauthenticated(get(PROTECTED, "Authorization", "Bearer not-a-key"));
        assertUnauthenticated(get(PROTECTED, "Authorization", "bearer " + ADMIN_KEY));
        assertUnauthenticated(getAs(ApiKeys.generate(), PROTECTED));
    }

    @Test
    void revokedKeysAndDisabledClientsStopWorkingOnTheNextRequest() {
        TestClient operator = newClient(ApiRole.OPERATOR);
        TestClient other = newClient(ApiRole.OPERATOR);
        String probe = "/api/v1/journal-transactions/jtx_01JC0000000000000000000000";
        assertThat(getAs(operator.token(), probe).statusCode()).isEqualTo(404);

        assertThat(postAs(ADMIN_KEY, "/api/v1/api-clients/" + operator.id() + "/keys/" + operator.keyId() + "/revoke",
                null).statusCode()).isEqualTo(204);
        assertUnauthenticated(getAs(operator.token(), probe));

        assertThat(postAs(ADMIN_KEY, "/api/v1/api-clients/" + other.id() + "/disable", null).statusCode())
                .isEqualTo(200);
        assertUnauthenticated(getAs(other.token(), probe));
    }

    @Test
    void aValidKeyWithTheWrongRoleIsForbiddenNotUnauthenticated() {
        HttpResponse<String> response = getAs(keyFor(ApiRole.CLIENT), PROTECTED);

        assertThat(response.statusCode()).isEqualTo(403);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(body.get("requestId").asString()).isNotBlank();
    }

    @Test
    void unlistedApiRoutesAreDeniedEvenToAdmins() {
        assertThat(getAs(ADMIN_KEY, "/api/v1/not-in-the-role-matrix").statusCode()).isEqualTo(403);
    }

    @Test
    void healthProbesArePublic() {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void rejectedTokensAreLoggedByPrefixOnly(CapturedOutput output) {
        String token = ApiKeys.generate();

        assertUnauthenticated(getAs(token, PROTECTED));

        assertThat(output.getAll()).contains(ApiKeys.displayPrefix(token)).doesNotContain(token);
    }

    private static void assertUnauthenticated(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("detail").asString()).isEqualTo("A valid API key is required.");
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    }
}
