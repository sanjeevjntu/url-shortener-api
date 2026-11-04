package com.example.shortener.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.TestProps;
import com.example.shortener.common.error.RateLimitedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void ipv4_isKeyedByAddress() {
        assertThat(RateLimiter.clientKey("203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void ipv6_isKeyedByItsSlash64_soRotatingAddressesDoesNotHelp() {
        String a = RateLimiter.clientKey("2001:db8:1234:5678::1");
        String b = RateLimiter.clientKey("2001:db8:1234:5678:ffff:eeee:dddd:cccc");
        String otherNetwork = RateLimiter.clientKey("2001:db8:1234:9999::1");

        assertThat(a).isEqualTo(b).isEqualTo("20010db812345678::/64");
        assertThat(otherNetwork).isNotEqualTo(a);
    }

    @Test
    void sameSlash64_sharesOneBucket() {
        RateLimiter limiter = new RateLimiter(TestProps.defaults(), new SimpleMeterRegistry()); // capacity 10
        for (int i = 0; i < 10; i++) {
            limiter.consumeOrThrow(RateLimitPolicy.CREATE, "2001:db8:1:2::" + Integer.toHexString(i + 1));
        }

        assertThatThrownBy(() -> limiter.consumeOrThrow(RateLimitPolicy.CREATE, "2001:db8:1:2::abcd"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void nonLiteral_isUsedVerbatim() {
        assertThat(RateLimiter.clientKey("not-an-ip")).isEqualTo("not-an-ip");
    }
}
