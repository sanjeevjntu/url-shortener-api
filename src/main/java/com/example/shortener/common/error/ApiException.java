package com.example.shortener.common.error;

import java.net.URI;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/// Every expected error the service raises: an [ErrorCode] plus a caller-safe detail message.
///
/// It extends Spring's `ErrorResponseException`, so [GlobalExceptionHandler] renders it as an RFC 9457
/// problem detail (`application/problem+json`), including any headers set on it such as `Retry-After`.
///
/// Usage: `throw new ApiException(ErrorCode.LINK_NOT_FOUND, "No link with code 'abc'")`.
public class ApiException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    public static final String TYPE_PREFIX = "https://errors.shortener.example/";

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String detail) {
        super(errorCode.status(), problem(errorCode, detail), null);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    private static ProblemDetail problem(ErrorCode errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        problem.setType(URI.create(TYPE_PREFIX + errorCode.slug()));
        problem.setTitle(errorCode.title());
        problem.setProperty("errorCode", errorCode.name());
        return problem;
    }
}
