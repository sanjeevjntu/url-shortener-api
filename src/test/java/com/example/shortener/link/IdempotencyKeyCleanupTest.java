package com.example.shortener.link;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.shortener.TestProps;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class IdempotencyKeyCleanupTest {

    @Test
    void purgesKeysOlderThanTheTtl() {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        IdempotencyRepository repo = mock(IdempotencyRepository.class);

        new IdempotencyKeyCleanup(repo, Clock.fixed(now, ZoneOffset.UTC), TestProps.defaults()).purgeExpiredKeys();

        verify(repo).deleteOlderThan(now.minus(Duration.ofHours(24)));
    }
}
