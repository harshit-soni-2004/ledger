package dev.ihars.ledger.shared.web;

import org.slf4j.MDC;

/** Request and correlation ids of the current request, already validated by {@link RequestCorrelationFilter}. */
public final class RequestContext {

    private RequestContext() {}

    public static String requestId() {
        return MDC.get(RequestCorrelationFilter.REQUEST_ID_KEY);
    }

    public static String correlationId() {
        return MDC.get(RequestCorrelationFilter.CORRELATION_ID_KEY);
    }
}
