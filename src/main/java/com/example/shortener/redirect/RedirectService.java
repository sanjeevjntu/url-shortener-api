package com.example.shortener.redirect;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/// Hot path: cache (then DB on miss) -> lazy expiry check -> [Resolution]. No writes happen here.
@Service
public class RedirectService {

    private final LinkCache cache;
    private final Clock clock;

    public RedirectService(LinkCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    public Resolution resolve(String code) {
        Instant now = clock.instant();
        return cache.get(code)
                .<Resolution>map(link -> link.isServableAt(now)
                        ? new Resolution.Redirect(link.code(), link.targetUrl())
                        : new Resolution.Gone(link.code()))
                .orElseGet(() -> new Resolution.NotFound(code));
    }
}
