package dev.ihars.ledger.shared.web;

import static dev.ihars.ledger.shared.web.RequestCorrelationFilter.CORRELATION_ID_HEADER;
import static dev.ihars.ledger.shared.web.RequestCorrelationFilter.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class CorrelationIdFilterIT extends IntegrationTest {

    private static final String PATH = "/actuator/health/liveness";

    @Test
    void generatesIdsWhenAbsent() {
        HttpResponse<String> response = get(PATH);

        String requestId = header(response, REQUEST_ID_HEADER);
        assertThat(requestId).matches("req_[0-9a-f]{32}");
        assertThat(header(response, CORRELATION_ID_HEADER)).isEqualTo(requestId);
    }

    @Test
    void generatesDistinctRequestIdsPerRequest() {
        assertThat(header(get(PATH), REQUEST_ID_HEADER)).isNotEqualTo(header(get(PATH), REQUEST_ID_HEADER));
    }

    @Test
    void echoesValidClientSuppliedIds() {
        HttpResponse<String> response = get(PATH,
                REQUEST_ID_HEADER, "client-req-42",
                CORRELATION_ID_HEADER, "checkout:flow.7");

        assertThat(header(response, REQUEST_ID_HEADER)).isEqualTo("client-req-42");
        assertThat(header(response, CORRELATION_ID_HEADER)).isEqualTo("checkout:flow.7");
    }

    @Test
    void replacesInvalidClientSuppliedIds() {
        HttpResponse<String> response = get(PATH,
                REQUEST_ID_HEADER, "not valid <script>",
                CORRELATION_ID_HEADER, "x".repeat(65));

        String requestId = header(response, REQUEST_ID_HEADER);
        assertThat(requestId).matches("req_[0-9a-f]{32}");
        assertThat(header(response, CORRELATION_ID_HEADER)).isEqualTo(requestId);
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElseThrow();
    }
}
