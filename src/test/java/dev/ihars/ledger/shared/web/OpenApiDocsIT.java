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
}
