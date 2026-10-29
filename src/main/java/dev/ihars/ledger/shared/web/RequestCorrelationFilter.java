package dev.ihars.ledger.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every HTTP request a request ID and a correlation ID, exposes both in the logging
 * MDC, and echoes them in the response headers.
 *
 * <p>Client-supplied values are accepted only if they match {@link #VALID_ID}; anything else is
 * replaced, so untrusted header content never reaches logs or stored records.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_KEY = "requestId";
    public static final String CORRELATION_ID_KEY = "correlationId";

    static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = validOrNull(request.getHeader(REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
        }
        String correlationId = validOrNull(request.getHeader(CORRELATION_ID_HEADER));
        if (correlationId == null) {
            correlationId = requestId;
        }

        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        MDC.put(REQUEST_ID_KEY, requestId);
        MDC.put(CORRELATION_ID_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_KEY);
            MDC.remove(CORRELATION_ID_KEY);
        }
    }

    private static String validOrNull(String value) {
        return value != null && VALID_ID.matcher(value).matches() ? value : null;
    }
}
