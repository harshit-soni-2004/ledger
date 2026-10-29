package dev.ihars.ledger.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void exposesIdsInMdcDuringRequestAndClearsThemAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "req-1");
        request.addHeader(RequestCorrelationFilter.CORRELATION_ID_HEADER, "flow-1");
        Map<String, String> seenInChain = new HashMap<>();
        FilterChain chain = (req, res) -> {
            seenInChain.put("requestId", MDC.get(RequestCorrelationFilter.REQUEST_ID_KEY));
            seenInChain.put("correlationId", MDC.get(RequestCorrelationFilter.CORRELATION_ID_KEY));
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenInChain).containsEntry("requestId", "req-1").containsEntry("correlationId", "flow-1");
        assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_KEY)).isNull();
        assertThat(MDC.get(RequestCorrelationFilter.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenTheChainFails() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            // the failure itself is not under test
        }

        assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID_KEY)).isNull();
    }

    @Test
    void acceptsOnlyBoundedSafeCharacters() {
        assertThat(RequestCorrelationFilter.VALID_ID.matcher("abc-123_X.y:z").matches()).isTrue();
        assertThat(RequestCorrelationFilter.VALID_ID.matcher("a".repeat(64)).matches()).isTrue();
        assertThat(RequestCorrelationFilter.VALID_ID.matcher("a".repeat(65)).matches()).isFalse();
        assertThat(RequestCorrelationFilter.VALID_ID.matcher("").matches()).isFalse();
        assertThat(RequestCorrelationFilter.VALID_ID.matcher("line\nbreak").matches()).isFalse();
        assertThat(RequestCorrelationFilter.VALID_ID.matcher("space here").matches()).isFalse();
    }
}
