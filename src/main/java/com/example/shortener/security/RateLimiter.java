package com.example.shortener.security;

import com.example.shortener.common.error.RateLimitedException;
import com.example.shortener.config.ShortenerProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;

/// Per-client token buckets (decision D8), using **Bucket4j** for the bucket maths.
///
/// One bucket per (policy, client), held in a size-bounded Caffeine cache with `expireAfterAccess`, so a flood
/// from random IPs cannot exhaust memory. State is per instance; a gateway or Redis-backed Bucket4j is the
/// scale-out path.
///
/// Why not Resilience4j's `RateLimiter`? It enforces one global limit per name, built to protect a downstream
/// dependency. It has no notion of "per client", which is what abuse protection needs.
@Component
public class RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private final Map<RateLimitPolicy, ShortenerProperties.Limit> limits;
    private final MeterRegistry registry;

    public RateLimiter(ShortenerProperties props, MeterRegistry registry) {
        this.limits = Map.of(
                RateLimitPolicy.CREATE, props.rateLimits().create(),
                RateLimitPolicy.REDIRECT, props.rateLimits().redirect(),
                RateLimitPolicy.AUTH_FAILURE, props.rateLimits().authFailure());
        this.registry = registry;
    }

    /// Anonymous traffic: takes one token from the client IP's bucket and returns how many remain, or throws `429`
    /// with `Retry-After`.
    public long consumeOrThrow(RateLimitPolicy policy, String clientIp) {
        return consume(policy, clientKey(clientIp));
    }

    /// Authenticated traffic: the bucket belongs to the API client, so the limit follows the key wherever it is
    /// used from (a leaked key spread over many IPs gets no more than the key's budget).
    public long consumeOrThrowForApiClient(RateLimitPolicy policy, String apiClient) {
        return consume(policy, "api-client:" + apiClient);
    }

    private long consume(RateLimitPolicy policy, String client) {
        Bucket bucket = buckets.get(policy.name() + '|' + client, _ -> newBucket(limits.get(policy)));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            registry.counter("shortener.ratelimit.rejected", "policy", policy.name())
                    .increment();
            throw new RateLimitedException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
        return probe.getRemainingTokens();
    }

    /// The identity a bucket is keyed by. IPv4: the address. IPv6: its **/64 prefix**, because one subscriber is
    /// typically given a whole /64 (billions of addresses); keying by full address would let a single client
    /// rotate addresses and bypass every limit.
    static String clientKey(String ip) {
        try {
            InetAddress address = InetAddress.ofLiteral(ip); // Java 22+: parses literals only, never does DNS
            if (address instanceof Inet6Address) {
                byte[] prefix = Arrays.copyOf(address.getAddress(), 8);
                return HexFormat.of().formatHex(prefix) + "::/64";
            }
            return address.getHostAddress();
        } catch (IllegalArgumentException _) {
            return ip; // not an IP literal (should not happen for a TCP peer); use it verbatim
        }
    }

    private static Bucket newBucket(ShortenerProperties.Limit limit) {
        // Burst of `capacity`, refilled smoothly at `refillPerMinute` (greedy = tokens trickle in continuously).
        return Bucket.builder()
                .addLimit(
                        l -> l.capacity(limit.capacity()).refillGreedy(limit.refillPerMinute(), Duration.ofMinutes(1)))
                .build();
    }
}
