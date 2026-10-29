package dev.ihars.ledger.shared.error;

import org.springframework.http.HttpStatus;

/** Thrown by web adapters to end a request with a specific Problem Details response. */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public ApiProblemException(HttpStatus status, ErrorCode code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static ApiProblemException notFound(String detail) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, detail);
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
