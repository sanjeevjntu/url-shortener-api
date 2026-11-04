package com.example.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClickAggregatorTest {

    private static final Instant LATE_DAY1 = Instant.parse("2026-10-06T23:59:59Z");
    private static final Instant EARLY_DAY2 = Instant.parse("2026-10-07T00:00:00Z");

    @Test
    void aggregatesPerCodeDayAndDimension_splittingDaysInUtc() {
        List<ClickAggregator.Increment> increments = ClickAggregator.aggregate(List.of(
                ClickEvent.of("B", LATE_DAY1, null, "curl/8"),
                ClickEvent.of("A", LATE_DAY1, null, "curl/8"),
                ClickEvent.of("A", LATE_DAY1, "https://x.com/post", "curl/8"),
                ClickEvent.of("A", EARLY_DAY2, null, "curl/8")));

        assertThat(clicks(increments, "A", LocalDate.of(2026, 10, 6), "total", ""))
                .isEqualTo(2);
        assertThat(clicks(increments, "A", LocalDate.of(2026, 10, 6), "referrer", "x.com"))
                .isEqualTo(1);
        assertThat(clicks(increments, "A", LocalDate.of(2026, 10, 6), "referrer", "direct"))
                .isEqualTo(1);
        assertThat(clicks(increments, "A", LocalDate.of(2026, 10, 7), "total", ""))
                .isEqualTo(1);
        assertThat(clicks(increments, "A", LocalDate.of(2026, 10, 6), "browser", "curl"))
                .isEqualTo(2);
    }

    @Test
    void outputIsSortedByKey_soConcurrentFlushesLockRowsInTheSameOrder() {
        List<ClickAggregator.Increment> increments = ClickAggregator.aggregate(
                List.of(ClickEvent.of("B", LATE_DAY1, null, null), ClickEvent.of("A", LATE_DAY1, null, null)));

        assertThat(increments.getFirst().key().code()).isEqualTo("A");
        assertThat(increments.getLast().key().code()).isEqualTo("B");
    }

    @Test
    void thousandClicksOnOneLink_becomeThreeUpserts() {
        List<ClickEvent> burst = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(_ -> ClickEvent.of("viral", LATE_DAY1, null, "curl/8"))
                .toList();

        assertThat(ClickAggregator.aggregate(burst)).hasSize(3); // total, referrer=direct, browser=curl
    }

    private static long clicks(
            List<ClickAggregator.Increment> increments, String code, LocalDate day, String dimension, String value) {
        ClickAggregator.Key key = new ClickAggregator.Key(code, day, dimension, value);
        return increments.stream()
                .filter(i -> i.key().equals(key))
                .mapToLong(ClickAggregator.Increment::clicks)
                .sum();
    }
}
