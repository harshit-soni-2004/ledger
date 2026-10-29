package dev.ihars.ledger.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/** springdoc-openapi 3.2 works with this Spring Boot line when the local profile enables it. */
@ActiveProfiles("local")
class OpenApiDocsIT extends IntegrationTest {

    @Test
    void servesOpenApiDocument() {
        HttpResponse<String> response = get("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"openapi\":\"3.");
    }

    @Test
    void documentsTheBearerSchemeAndThePhase4Endpoints() {
        String body = get("/v3/api-docs").body();

        assertThat(body).contains("\"apiKey\"").contains("\"scheme\":\"bearer\"")
                .contains("/api/v1/accounts/{accountId}/entries")
                .contains("/api/v1/journal-transactions/openings")
                .contains("/api/v1/ops/integrity-checks/latest")
                .contains("/api/v1/api-clients");
    }
}
