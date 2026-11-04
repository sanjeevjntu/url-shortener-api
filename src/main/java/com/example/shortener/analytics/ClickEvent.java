package com.example.shortener.analytics;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/// One redirect, reduced to what analytics needs. **No IP address and no full referrer URL** are kept
/// (decision D4): only the referrer's host and a coarse browser family.
public record ClickEvent(String code, Instant at, String referrerHost, String browser) {

    public ClickEvent {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(referrerHost, "referrerHost");
        Objects.requireNonNull(browser, "browser");
    }

    public static ClickEvent of(String code, Instant at, String refererHeader, String userAgentHeader) {
        return new ClickEvent(code, at, referrerHost(refererHeader), browserFamily(userAgentHeader));
    }

    /// `"https://news.example.com/a?b"` becomes `"news.example.com"`; missing or unparsable becomes `"direct"`.
    static String referrerHost(String referer) {
        if (referer == null || referer.isBlank() || referer.length() > 2048) {
            return "direct";
        }
        try {
            String host = new URI(referer.strip()).getHost();
            return host == null ? "direct" : truncate(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException _) {
            return "direct";
        }
    }

    /// Coarse family only. Order matters: Edge and Opera also say "Chrome", Chrome also says "Safari".
    static String browserFamily(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")) {
            return "bot";
        }
        if (ua.contains("edg/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        if (ua.startsWith("curl/")) {
            return "curl";
        }
        return "other";
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
