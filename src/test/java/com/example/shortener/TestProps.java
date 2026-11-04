package com.example.shortener;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.config.ShortenerProperties.Analytics;
import com.example.shortener.security.ApiScopes;
import java.time.Duration;
import java.util.List;

/** Shared configuration for unit tests, mirroring application.yml defaults. */
public final class TestProps {

    public static final String API_KEY = "test-api-key";
    /** SHA-256 of {@link #API_KEY}; the same value is configured for integration tests. */
    public static final String API_KEY_SHA256 = "4c806362b613f7496abf284146efd31da90e4b16169fe001841ca17290f427c4";

    /** Integration tests only: a key with {@code stats:read} only (cannot create or delete). */
    public static final String READONLY_KEY = "readonly-key";

    public static final String READONLY_KEY_SHA256 = "0c88e963410f1f94f5a78faaff79e1b9f7b8a893c79b0b191f03cf2e6f003454";

    /** Integration tests only: a key with {@code links:create} only (a second client that can create). */
    public static final String CREATOR_KEY = "creator-key";

    public static final String CREATOR_KEY_SHA256 = "48bf566c048fe262e19920019b9854697389e4fa0cdf8a9c792f8c36a49efddd";

    /** Integration tests only: a key whose expiry is in the past. */
    public static final String EXPIRED_KEY = "expired-key";

    public static final String EXPIRED_KEY_SHA256 = "85470b1932ebf421241eb5df4d4c8e71a40501cf7d5e198907980c6b750ef78e";

    private TestProps() {}

    public static ShortenerProperties defaults() {
        return build(5, 100);
    }

    public static ShortenerProperties withMaxAttempts(int maxAttempts) {
        return build(maxAttempts, 100);
    }

    public static ShortenerProperties withQueueCapacity(int queueCapacity) {
        return build(5, queueCapacity);
    }

    private static ShortenerProperties build(int maxAttempts, int queueCapacity) {
        var limit = new ShortenerProperties.Limit(10, 10);
        return new ShortenerProperties(
                "http://localhost:8080",
                List.of(new ShortenerProperties.ApiKey("tests", API_KEY_SHA256, List.copyOf(ApiScopes.ALL), null)),
                new ShortenerProperties.Code(8, maxAttempts),
                new ShortenerProperties.Cache(100, Duration.ofMinutes(5), Duration.ofSeconds(30)),
                new Analytics(Analytics.Mode.ASYNC, queueCapacity, 1000),
                new ShortenerProperties.RateLimits(limit, limit, limit),
                Duration.ofHours(24));
    }
}
