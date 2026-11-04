package com.example.shortener.analytics.web;

import com.example.shortener.analytics.ClickStatsRepository.DailyCount;
import com.example.shortener.analytics.ClickStatsRepository.NamedCount;
import java.time.LocalDate;
import java.util.List;

/// Click analytics for one link over `[from, to]` (UTC days).
public record StatsResponse(
        String code,
        LocalDate from,
        LocalDate to,
        long totalClicks,
        List<DailyCount> daily,
        List<NamedCount> topReferrers,
        List<NamedCount> browsers) {

    public StatsResponse {
        daily = List.copyOf(daily);
        topReferrers = List.copyOf(topReferrers);
        browsers = List.copyOf(browsers);
    }
}
