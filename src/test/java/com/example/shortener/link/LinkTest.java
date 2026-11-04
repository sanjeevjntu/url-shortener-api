package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LinkTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private static Link link(LinkStatus status, Instant expiresAt) {
        return new Link(1, "Abc12345", "https://example.com/", status, NOW.minusSeconds(60), expiresAt, null);
    }

    @Test
    void noExpiry_isServable() {
        assertThat(link(LinkStatus.ACTIVE, null).isServableAt(NOW)).isTrue();
    }

    @Test
    void expiryExactlyNow_isExpired_boundaryIsExclusive() {
        assertThat(link(LinkStatus.ACTIVE, NOW).isExpiredAt(NOW)).isTrue();
        assertThat(link(LinkStatus.ACTIVE, NOW.plusNanos(1)).isExpiredAt(NOW)).isFalse();
    }

    @Test
    void expiredButStillActiveInDb_isNotServable() {
        assertThat(link(LinkStatus.ACTIVE, NOW.minusSeconds(1)).isServableAt(NOW))
                .isFalse();
    }

    @Test
    void nonActiveStatuses_areNotServable() {
        for (LinkStatus s : new LinkStatus[] {LinkStatus.DISABLED, LinkStatus.EXPIRED, LinkStatus.DELETED}) {
            assertThat(link(s, null).isServableAt(NOW)).as(s.name()).isFalse();
        }
    }
}
