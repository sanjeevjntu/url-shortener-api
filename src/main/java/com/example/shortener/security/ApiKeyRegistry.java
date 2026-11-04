package com.example.shortener.security;

import com.example.shortener.common.Fingerprints;
import com.example.shortener.config.ShortenerProperties;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/// The configured API clients, and the lookup of a presented key (decision D6).
///
/// **No key configured:**
/// - in the `local` profile (developer machine, demo; the default when started from a checkout, see
///   `config/application.yml`), a random key is generated for this run and printed once in the log, the same approach
///   Spring Security takes with its generated default password. It changes on every restart and is never stored;
/// - in every other profile, startup **fails**. Production can never run without explicitly configured key hashes.
@Component
public final class ApiKeyRegistry { // final: the constructor may throw (fail-fast), see SpotBugs CT_CONSTRUCTOR_THROW

    public static final String LOCAL_PROFILE = "local";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);
    private static final Duration EXPIRY_WARNING = Duration.ofDays(14);
    private static final SecureRandom RANDOM = new SecureRandom();

    /// A client after a successful match. No secrets in it, so it is safe to pass around and log.
    public record ApiClient(String name, Set<String> scopes, Instant expiresAt) {

        public ApiClient {
            scopes = Set.copyOf(scopes);
        }

        public boolean isExpiredAt(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }

    private final List<Entry> entries;

    public ApiKeyRegistry(ShortenerProperties props, Environment environment, Clock clock) {
        List<ShortenerProperties.ApiKey> keys = props.apiKeys();
        if (keys.isEmpty()) {
            keys = List.of(generatedDevKey(environment));
        }
        this.entries = keys.stream()
                .map(k -> new Entry(
                        HexFormat.of().parseHex(k.sha256()),
                        new ApiClient(k.name(), Set.copyOf(k.scopes()), k.expiresAt())))
                .toList();
        warnAboutExpiringKeys(clock.instant());
    }

    /// Finds the client for a presented key. Compares against **every** entry in constant time (no early exit), so
    /// the time taken reveals neither how much of a guess matched nor which client it was close to.
    public Optional<ApiClient> match(String presentedKey) {
        byte[] digest = Fingerprints.sha256(presentedKey);
        ApiClient matched = null;
        for (Entry e : entries) {
            if (MessageDigest.isEqual(e.keyHash, digest)) {
                matched = e.client;
            }
        }
        return Optional.ofNullable(matched);
    }

    /// Names of the configured clients (for diagnostics and tests; never the keys).
    public List<String> clientNames() {
        return entries.stream().map(e -> e.client.name()).toList();
    }

    private static ShortenerProperties.ApiKey generatedDevKey(Environment environment) {
        if (!environment.matchesProfiles(LOCAL_PROFILE)) {
            throw new IllegalStateException("No API key configured (shortener.api-keys). Deployments set "
                    + "SHORTENER_APIKEYS_0_NAME, SHORTENER_APIKEYS_0_SHA256 and SHORTENER_APIKEYS_0_SCOPES (see "
                    + "scripts/new-api-key.sh). For local development start the app with the project root as working "
                    + "directory (config/application.yml then selects the 'local' profile and a key is generated), "
                    + "or set the 'local' profile explicitly.");
        }
        byte[] random = new byte[30];
        RANDOM.nextBytes(random);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(random); // 40 characters
        log.warn(
                """

                ======================================================================
                  No API key configured: generated one for THIS RUN (profile "local").
                    X-API-Key: {}
                  Development/demo only. It changes on every restart.
                  For a stable key run: scripts/new-api-key.sh dev --local
                ======================================================================""",
                key);
        return new ShortenerProperties.ApiKey(
                "dev", Fingerprints.hex(Fingerprints.sha256(key)), List.copyOf(ApiScopes.ALL), null);
    }

    private void warnAboutExpiringKeys(Instant now) {
        Instant soon = now.plus(EXPIRY_WARNING);
        for (Entry e : entries) {
            Instant expiresAt = e.client.expiresAt();
            if (expiresAt != null && expiresAt.isBefore(soon)) {
                log.warn("API key '{}' expires at {}: rotate it (scripts/new-api-key.sh)", e.client.name(), expiresAt);
            }
        }
    }

    /// A class rather than a record so the hash array is never exposed.
    private static final class Entry {
        private final byte[] keyHash;
        private final ApiClient client;

        Entry(byte[] keyHash, ApiClient client) {
            this.keyHash = keyHash.clone();
            this.client = client;
        }
    }
}
