package dev.ihars.ledger.security.api;

import dev.ihars.ledger.security.ApiClientRejectedException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.error.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityExceptionHandler {

    @ExceptionHandler(ApiClientRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(ApiClientRejectedException e) {
        return switch (e.code()) {
            case INVALID_NAME, INVALID_ROLE ->
                    Problems.response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, e.getMessage());
            case CLIENT_NOT_FOUND, KEY_NOT_FOUND ->
                    Problems.response(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, e.getMessage());
            case CLIENT_DISABLED -> Problems.response(HttpStatus.CONFLICT, ErrorCode.CLIENT_DISABLED, e.getMessage());
            case TOO_MANY_ACTIVE_KEYS ->
                    Problems.response(HttpStatus.CONFLICT, ErrorCode.TOO_MANY_ACTIVE_KEYS, e.getMessage());
        };
    }
}
