package com.example.shortener.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/// Gives every request a trace ID: reused from an incoming `X-Trace-Id` header (if well formed) or generated.
///
/// The ID goes into the logging MDC (every log line of the request shows it), into the `X-Trace-Id` response
/// header, and into every error body. A user reporting an error can quote it, and support finds the log lines.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && VALID.matcher(incoming).matches() ? incoming : newId();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // threads are reused: never leak an ID into the next request
        }
    }

    /// 64 random bits as 16 hex chars: unique enough to correlate logs, and not a secret (no SecureRandom needed).
    private static String newId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }
}
