package com.example.shortener.common.error;

import java.time.Duration;
import org.springframework.http.HttpHeaders;

/// `429 Too Many Requests` with a `Retry-After` header (whole seconds, at least 1).
public final class RateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;

    public RateLimitedException(Duration retryAfter) {
        // Java 25 flexible constructor bodies (JEP 513): compute the value before calling super().
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        super(ErrorCode.RATE_LIMITED, "Retry in " + seconds + "s");
        getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    }
}
