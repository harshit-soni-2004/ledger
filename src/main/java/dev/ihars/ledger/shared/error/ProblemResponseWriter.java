package dev.ihars.ledger.shared.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes Problem Details from servlet filters, where {@code @RestControllerAdvice} does not apply. */
@Component
public class ProblemResponseWriter {

    private final JsonMapper json;

    public ProblemResponseWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, HttpStatus status, ErrorCode code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Problems.asMap(status, code, detail)));
    }
}
