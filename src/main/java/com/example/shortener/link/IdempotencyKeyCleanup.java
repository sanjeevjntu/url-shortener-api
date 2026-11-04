package com.example.shortener.link;

import com.example.shortener.config.ShortenerProperties;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/// Deletes idempotency keys older than `shortener.idempotency-ttl` (default 24 h) so the table stays small.
/// Safe to run on every instance at once (a plain `DELETE`).
@Component
public class IdempotencyKeyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanup.class);

    private final IdempotencyRepository idempotency;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyKeyCleanup(IdempotencyRepository idempotency, Clock clock, ShortenerProperties props) {
        this.idempotency = idempotency;
        this.clock = clock;
        this.ttl = props.idempotencyTtl();
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    public void purgeExpiredKeys() {
        int deleted = idempotency.deleteOlderThan(clock.instant().minus(ttl));
        if (deleted > 0) {
            log.info("Purged {} idempotency keys", deleted);
        }
    }
}
