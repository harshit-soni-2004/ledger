package dev.ihars.ledger.accounts.api;

import dev.ihars.ledger.accounts.AccountRejectedException;
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
class AccountExceptionHandler {

    @ExceptionHandler(AccountRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(AccountRejectedException e) {
        return switch (e.code()) {
            case INVALID_NAME, INVALID_TYPE, INVALID_OVERDRAFT, REASON_REQUIRED ->
                    Problems.response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, e.getMessage());
            case UNSUPPORTED_CURRENCY ->
                    Problems.response(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.UNSUPPORTED_CURRENCY, e.getMessage());
            case ACCOUNT_NOT_FOUND ->
                    Problems.response(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND, "Account not found.");
            case SYSTEM_ACCOUNT, STATUS_CONFLICT ->
                    Problems.response(HttpStatus.CONFLICT, ErrorCode.ACCOUNT_STATUS_CONFLICT, e.getMessage());
            case BALANCE_NOT_ZERO ->
                    Problems.response(HttpStatus.CONFLICT, ErrorCode.ACCOUNT_BALANCE_NOT_ZERO, e.getMessage());
        };
    }
}
