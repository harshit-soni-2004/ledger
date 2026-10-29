package dev.ihars.ledger.shared.error;

import dev.ihars.ledger.shared.web.RequestCorrelationFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** Builds Problem Details bodies with the stable {@code code} and the current request id. */
public final class Problems {

    private Problems() {}

    public static ProblemDetail of(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(code.type());
        problem.setProperty("code", code.name());
        problem.setProperty("requestId", MDC.get(RequestCorrelationFilter.REQUEST_ID_KEY));
        return problem;
    }

    public static ResponseEntity<ProblemDetail> response(HttpStatus status, ErrorCode code, String detail) {
        return ResponseEntity.status(status).body(of(status, code, detail));
    }

    /** The same body as a plain map, for writers outside Spring MVC (security filters). */
    public static Map<String, Object> asMap(HttpStatus status, ErrorCode code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.type().toString());
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code.name());
        body.put("requestId", MDC.get(RequestCorrelationFilter.REQUEST_ID_KEY));
        return body;
    }
}
