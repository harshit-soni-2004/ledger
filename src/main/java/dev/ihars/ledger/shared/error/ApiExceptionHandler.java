package dev.ihars.ledger.shared.error;

import dev.ihars.ledger.shared.web.RequestCorrelationFilter;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 9457 Problem Details with a stable {@code code} and the
 * {@code requestId}. Server errors never expose exception messages or stack traces.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<ProblemDetail> handleApiProblem(ApiProblemException ex) {
        return Problems.response(ex.status(), ex.code(), ex.getMessage());
    }

    /** Lock-wait timeout or deadlock victim: the transaction rolled back and is safe to retry. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleLockFailure(PessimisticLockingFailureException ex) {
        log.warn("Request rolled back after a lock conflict: {}", ex.getClass().getSimpleName());
        ResponseEntity<ProblemDetail> response = Problems.response(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.RESOURCE_BUSY, "The resource is busy; retry the request shortly.");
        return ResponseEntity.status(response.getStatusCode()).header(HttpHeaders.RETRY_AFTER, "1")
                .body(response.getBody());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing request", ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setDetail("Request validation failed.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ErrorCode code = isValidationFailure(ex) ? ErrorCode.VALIDATION_FAILED : ErrorCode.fromStatus(statusCode);
            problem.setType(code.type());
            problem.setProperty("code", code.name());
            problem.setProperty("requestId", MDC.get(RequestCorrelationFilter.REQUEST_ID_KEY));
            if (statusCode.is5xxServerError()) {
                problem.setDetail("An unexpected error occurred.");
            }
        }
        return response;
    }

    private static boolean isValidationFailure(Exception ex) {
        return ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException;
    }
}
