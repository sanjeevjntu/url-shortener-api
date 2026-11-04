package com.example.shortener.common.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/// The API's error vocabulary: one place that fixes the HTTP status, title and machine-readable code of every
/// error the service raises itself. Clients switch on `errorCode`, never on the human-readable `detail`.
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    INVALID_URL(HttpStatus.BAD_REQUEST, "Invalid URL"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "Link not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused"),
    LINK_GONE(HttpStatus.GONE, "Link expired or deleted"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
    CODE_ALLOCATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Could not allocate a short code");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /// `LINK_NOT_FOUND` becomes `link-not-found`, used in the problem `type` URI.
    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /// Code for errors raised by Spring itself (malformed JSON, unknown route, wrong method, ...).
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : INVALID_REQUEST;
        };
    }
}
