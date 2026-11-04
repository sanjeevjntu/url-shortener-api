package com.example.shortener.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/// Applies one [RateLimitPolicy] to the paths it is registered for (see `WebConfig`).
///
/// - **Authenticated requests** (the API key interceptor runs first and names the client) are limited per API
///   client: creating links.
/// - **Anonymous requests** are limited per client IP, the TCP peer: redirects. `X-Forwarded-For` is ignored unless
///   the deployment turns on Spring's forwarded-header support for its own load balancer, so clients cannot spoof
///   their IP.
///
/// Runs inside the DispatcherServlet, so a `429` goes through the global exception handler like every error.
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final RateLimitPolicy policy;

    public RateLimitInterceptor(RateLimiter rateLimiter, RateLimitPolicy policy) {
        this.rateLimiter = rateLimiter;
        this.policy = policy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long remaining = request.getAttribute(ApiKeyInterceptor.CLIENT_ATTRIBUTE) instanceof String apiClient
                ? rateLimiter.consumeOrThrowForApiClient(policy, apiClient)
                : rateLimiter.consumeOrThrow(policy, request.getRemoteAddr());
        response.setHeader("X-RateLimit-Remaining", Long.toString(remaining));
        return true;
    }
}
