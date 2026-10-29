package dev.ihars.ledger.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.shared.web.RequestCorrelationFilter;
import dev.ihars.ledger.support.IntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(ProblemDetailsIT.ProbeConfiguration.class)
class ProblemDetailsIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void unknownRouteReturnsNotFoundProblem() {
        HttpResponse<String> response = getAs(ADMIN_KEY, "/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertProblem(response, "NOT_FOUND", "https://ledger.local/problems/not-found");
    }

    @Test
    void wrongMethodReturnsMethodNotAllowedProblem() {
        HttpResponse<String> response = getAs(ADMIN_KEY, "/test/problems/validated");

        assertThat(response.statusCode()).isEqualTo(405);
        assertProblem(response, "METHOD_NOT_ALLOWED", "https://ledger.local/problems/method-not-allowed");
    }

    @Test
    void beanValidationFailureListsFieldErrors() {
        HttpResponse<String> response = postAs(ADMIN_KEY, "/test/problems/validated", "{\"name\": \"\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = assertProblem(response, "VALIDATION_FAILED", "https://ledger.local/problems/validation-failed");
        assertThat(body.get("errors").get(0).get("field").asString()).isEqualTo("name");
    }

    @Test
    void malformedJsonReturnsBadRequestProblem() {
        HttpResponse<String> response = postAs(ADMIN_KEY, "/test/problems/validated", "{not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertProblem(response, "BAD_REQUEST", "https://ledger.local/problems/bad-request");
    }

    @Test
    void unsupportedContentTypeReturnsUnsupportedMediaTypeProblem() {
        HttpResponse<String> response = call("POST", "/test/problems/validated", ADMIN_KEY, null,
                "Content-Type", "text/plain");

        assertThat(response.statusCode()).isEqualTo(415);
        assertProblem(response, "UNSUPPORTED_MEDIA_TYPE", "https://ledger.local/problems/unsupported-media-type");
    }

    @Test
    void unknownJsonFieldsAreRejected() {
        HttpResponse<String> response = postAs(ADMIN_KEY, "/test/problems/validated", "{\"name\": \"a\", \"nmae\": \"b\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertProblem(response, "BAD_REQUEST", "https://ledger.local/problems/bad-request");
    }

    @Test
    void unauthenticatedRequestsGetAProblemFromTheSecurityLayer() {
        HttpResponse<String> response = get("/test/problems/explode");

        assertThat(response.statusCode()).isEqualTo(401);
        assertProblem(response, "UNAUTHENTICATED", "https://ledger.local/problems/unauthenticated");
    }

    @Test
    void unexpectedErrorsHideInternalDetails() {
        HttpResponse<String> response = getAs(ADMIN_KEY, "/test/problems/explode");

        assertThat(response.statusCode()).isEqualTo(500);
        JsonNode body = assertProblem(response, "INTERNAL_ERROR", "https://ledger.local/problems/internal-error");
        assertThat(body.get("detail").asString()).isEqualTo("An unexpected error occurred.");
        assertThat(response.body()).doesNotContain("secret-internal-state").doesNotContain("Exception");
    }

    private static JsonNode assertProblem(HttpResponse<String> response, String code, String type) {
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/problem+json"));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("type").asString()).isEqualTo(type);
        assertThat(body.get("status").asInt()).isEqualTo(response.statusCode());
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue(RequestCorrelationFilter.REQUEST_ID_HEADER).orElseThrow());
        return body;
    }

    @TestConfiguration
    static class ProbeConfiguration {

        @RestController
        static class ProblemProbeController {

            record Payload(@NotBlank String name) {}

            @PostMapping("/test/problems/validated")
            Payload validated(@Valid @RequestBody Payload payload) {
                return payload;
            }

            @GetMapping("/test/problems/explode")
            String explode() {
                throw new IllegalStateException("secret-internal-state");
            }
        }
    }
}
