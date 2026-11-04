package com.example.shortener.config;

import com.example.shortener.security.ApiScopes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/// All tunables in one typed, validated place (`shortener.*`). Invalid or missing config stops the app at startup.
@Validated
@ConfigurationProperties("shortener")
public record ShortenerProperties(
        @NotBlank String baseUrl,
        List<@Valid ApiKey> apiKeys,
        @Valid @NotNull Code code,
        @Valid @NotNull Cache cache,
        @Valid @NotNull Analytics analytics,
        @Valid @NotNull RateLimits rateLimits,
        @NotNull Duration idempotencyTtl) {

    /// No keys here is allowed only in the `local` profile, where `ApiKeyRegistry` generates one for the run;
    /// in every other profile `ApiKeyRegistry` refuses to start.
    public ShortenerProperties {
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
    }

    /// A management client. Only the SHA-256 hash of its key is configured: the key itself never appears in the
    /// repository, configuration or logs. `name` identifies the caller in logs and in `links.deleted_by`.
    /// `scopes` grant permissions (least privilege); `expiresAt` (optional) forces rotation.
    public record ApiKey(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String name,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "must be a lowercase hex SHA-256 hash") String sha256,
            @NotEmpty List<@Pattern(regexp = ApiScopes.PATTERN, message = "unknown scope") String> scopes,
            Instant expiresAt) {

        public ApiKey {
            // Immutable copy; a missing list becomes empty so @NotEmpty reports it clearly instead of a NPE.
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    /// Code generation (decision D3). 8 chars = 58^8, about 1.28e14 codes.
    public record Code(@Min(6) @Max(10) int length, @Min(1) @Max(10) int maxAttempts) {}

    /// Redirect cache (decision D2). Negative entries (unknown codes) live briefly to blunt scanning.
    public record Cache(@Min(1) long maxSize, @NotNull Duration ttl, @NotNull Duration negativeTtl) {}

    /// `async` = bounded queue + batch flusher (default); `sync` = write per click (baseline / rollback).
    public record Analytics(@NotNull Mode mode, @Min(1) int queueCapacity, @Min(1) int maxBatch) {
        public enum Mode {
            ASYNC,
            SYNC
        }
    }

    /// `create` is strict and per API key; `redirect` is lenient and per client IP; `authFailure` (per client IP)
    /// throttles API-key guessing: only failed attempts consume it, so legitimate clients are never slowed.
    public record RateLimits(
            @Valid @NotNull Limit create, @Valid @NotNull Limit redirect, @Valid @NotNull Limit authFailure) {}

    public record Limit(@Min(1) long capacity, @Min(1) long refillPerMinute) {}
}
