package com.example.shortener.analytics;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Collapses a batch of click events into counter increments, so a viral link costs **one upsert per flush**
/// instead of one write per click (decision D4, removes hot-row contention).
///
/// Output is sorted by key. Every writer then locks rows in the same order, which rules out deadlocks
/// between concurrent flushes (several instances, or a slow flush overlapping the next one).
public final class ClickAggregator {

    /// Upsert key in `link_click_stats`. `dimension` is `total`, `referrer` or `browser`.
    public record Key(String code, LocalDate day, String dimension, String value) {}

    public record Increment(Key key, long clicks) {}

    private static final Comparator<Key> ORDER = Comparator.comparing(Key::code)
            .thenComparing(Key::dimension)
            .thenComparing(Key::day)
            .thenComparing(Key::value);

    private ClickAggregator() {}

    public static List<Increment> aggregate(Collection<ClickEvent> events) {
        Map<Key, Long> counts = new TreeMap<>(ORDER);
        for (ClickEvent e : events) {
            LocalDate day = LocalDate.ofInstant(e.at(), ZoneOffset.UTC);
            counts.merge(new Key(e.code(), day, "total", ""), 1L, Long::sum);
            counts.merge(new Key(e.code(), day, "referrer", e.referrerHost()), 1L, Long::sum);
            counts.merge(new Key(e.code(), day, "browser", e.browser()), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new Increment(entry.getKey(), entry.getValue()))
                .toList();
    }
}
