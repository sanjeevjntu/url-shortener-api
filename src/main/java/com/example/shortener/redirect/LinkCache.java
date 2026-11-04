package com.example.shortener.redirect;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.link.Link;
import com.example.shortener.link.LinkChangedEvent;
import com.example.shortener.link.LinkRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/// In-process cache in front of the database for the redirect hot path (decision D2).
///
/// - **Single-flight:** `LoadingCache.get` runs at most one database load per code at a time, so a viral
///   link that is not cached yet costs one query, not thousands (no cache stampede).
/// - **Negative caching:** unknown codes are cached as `Optional.empty()` with a short TTL, so scanners mostly
///   hit memory, not Postgres.
/// - **Correctness:** expiry is re-checked on every read (`Link.isServableAt`), so a cached link never outlives
///   its `expiresAt`. Deletes evict locally; other instances converge within `ttl` (keep it short).
/// - **Resilience:** if Postgres is down, cached codes keep redirecting.
@Component
public class LinkCache {

    private final LoadingCache<String, Optional<Link>> cache;

    public LinkCache(LinkRepository links, ShortenerProperties props, MeterRegistry registry) {
        ShortenerProperties.Cache config = props.cache();
        long hitNanos = config.ttl().toNanos();
        long missNanos = config.negativeTtl().toNanos();
        this.cache = Caffeine.newBuilder()
                .maximumSize(config.maxSize())
                .expireAfter(new Expiry<String, Optional<Link>>() {
                    @Override
                    public long expireAfterCreate(String code, Optional<Link> link, long currentTime) {
                        return link.isPresent() ? hitNanos : missNanos;
                    }

                    @Override
                    public long expireAfterUpdate(
                            String code, Optional<Link> link, long currentTime, long currentDuration) {
                        return expireAfterCreate(code, link, currentTime);
                    }

                    @Override
                    public long expireAfterRead(
                            String code, Optional<Link> link, long currentTime, long currentDuration) {
                        return currentDuration; // reads do not extend lifetime
                    }
                })
                .recordStats()
                .build(links::findByCode);
        CaffeineCacheMetrics.monitor(registry, cache, "links"); // hit ratio, evictions, load time
    }

    public Optional<Link> get(String code) {
        return cache.get(code);
    }

    @EventListener
    public void onLinkChanged(LinkChangedEvent event) {
        cache.invalidate(event.code());
    }
}
