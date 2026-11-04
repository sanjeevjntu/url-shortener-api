package com.example.shortener.security;

import com.example.shortener.common.error.ApiException;
import com.example.shortener.common.error.ErrorCode;
import com.example.shortener.security.ApiKeyRegistry.ApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/// Guards `@RequiresApiKey` endpoints (decision D6). Keys and their lookup live in [ApiKeyRegistry].
///
/// - `401`: no key, unknown key, or expired key. `403`: valid key without the endpoint's scope (least privilege).
/// - **Brute-force resistance:** every failure is logged, counted (`shortener.auth.failures`) and consumes the
///   per-IP `AUTH_FAILURE` bucket; when it is empty the caller gets `429`. Successful calls never spend it.
/// - On success the client's name is stored as a request attribute, so controllers can audit who acted.
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-API-Key";
    public static final String CLIENT_ATTRIBUTE = "shortener.apiClient";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyInterceptor.class);

    private final ApiKeyRegistry keys;
    private final RateLimiter rateLimiter;
    private final MeterRegistry registry;
    private final Clock clock;

    public ApiKeyInterceptor(ApiKeyRegistry keys, RateLimiter rateLimiter, MeterRegistry registry, Clock clock) {
        this.keys = keys;
        this.rateLimiter = rateLimiter;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresApiKey required = method.getMethodAnnotation(RequiresApiKey.class);
        if (required == null) {
            return true;
        }
        String presented = request.getHeader(HEADER);
        ApiClient client = presented == null ? null : keys.match(presented).orElse(null);
        if (client == null) {
            reject(
                    request,
                    presented == null ? "missing" : "invalid",
                    ErrorCode.UNAUTHORIZED,
                    "A valid " + HEADER + " header is required");
        } else if (client.isExpiredAt(clock.instant())) {
            reject(request, "expired", ErrorCode.UNAUTHORIZED, "API key '" + client.name() + "' has expired");
        } else if (!client.scopes().contains(required.scope())) {
            reject(
                    request,
                    "forbidden",
                    ErrorCode.FORBIDDEN,
                    "API key '" + client.name() + "' lacks scope '" + required.scope() + "'");
        } else {
            request.setAttribute(CLIENT_ATTRIBUTE, client.name());
        }
        return true;
    }

    /// Logs and counts the failure, consumes the caller's failure budget (429 when exhausted), then rejects.
    private void reject(HttpServletRequest request, String reason, ErrorCode code, String detail) {
        log.warn(
                "API key rejected ({}) on {} {} from {}",
                reason,
                request.getMethod(),
                request.getRequestURI(),
                request.getRemoteAddr());
        registry.counter("shortener.auth.failures", "reason", reason).increment();
        rateLimiter.consumeOrThrow(RateLimitPolicy.AUTH_FAILURE, request.getRemoteAddr());
        throw new ApiException(code, detail);
    }
}
