package dev.ihars.ledger.journals.api;

import dev.ihars.ledger.ledger.domain.JournalRejectedException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.error.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Accounting rejections as 422. Details are fixed per code because domain messages name
 * internal account ids, which are never exposed.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class JournalExceptionHandler {

    @ExceptionHandler(JournalRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(JournalRejectedException e) {
        ResponseEntity<ProblemDetail> response = switch (e.code()) {
            case CURRENCY_MISMATCH -> problem(ErrorCode.CURRENCY_MISMATCH,
                    "Every account and line must be in the journal currency.");
            case EFFECTIVE_DATE_IN_FUTURE -> problem(ErrorCode.EFFECTIVE_DATE_IN_FUTURE,
                    "The effective date is after today's business date.");
            case ACCOUNT_NOT_FOUND -> problem(ErrorCode.ACCOUNT_NOT_FOUND, "An account in the journal does not exist.");
            case ACCOUNT_NOT_ACTIVE -> problem(ErrorCode.ACCOUNT_NOT_ACTIVE, "An account in the journal is not ACTIVE.");
            case INSUFFICIENT_FUNDS -> problem(ErrorCode.INSUFFICIENT_FUNDS,
                    "An account would fall below its minimum balance.");
            case OPENING_NOT_ALLOWED -> problem(ErrorCode.OPENING_NOT_ALLOWED,
                    "Opening balances may only be posted to accounts without entries.");
            case TOO_FEW_LINES -> problem(ErrorCode.JOURNAL_REJECTED, "A journal needs at least two lines.");
            case TOO_MANY_LINES -> problem(ErrorCode.JOURNAL_REJECTED, "The journal has too many lines.");
            case UNBALANCED -> problem(ErrorCode.JOURNAL_REJECTED, "Debits do not equal credits.");
            case SAME_ACCOUNT_BOTH_SIDES -> problem(ErrorCode.JOURNAL_REJECTED,
                    "An account may not be both debited and credited in one journal.");
            case REASON_REQUIRED -> problem(ErrorCode.JOURNAL_REJECTED, "A reason is required.");
            case INVALID_FIELD -> problem(ErrorCode.JOURNAL_REJECTED,
                    "description, reason or externalReference is invalid.");
            case AMOUNT_OUT_OF_RANGE -> problem(ErrorCode.JOURNAL_REJECTED, "An amount is out of the supported range.");
            case CANNOT_REVERSE_REVERSAL, REVERSAL_NOT_SUPPORTED -> problem(ErrorCode.JOURNAL_REJECTED,
                    "Reversals are not supported.");
        };
        if (response.getBody() != null) {
            response.getBody().setProperty("rule", e.code().name());
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail) {
        return Problems.response(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }
}
