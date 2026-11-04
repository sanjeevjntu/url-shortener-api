package com.example.shortener.analytics.web;

import com.example.shortener.analytics.ClickStatsRepository;
import com.example.shortener.analytics.ClickStatsRepository.DailyCount;
import com.example.shortener.config.OpenApiConfig;
import com.example.shortener.link.Link;
import com.example.shortener.link.LinkService;
import com.example.shortener.security.ApiScopes;
import com.example.shortener.security.RequiresApiKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// `GET /api/v1/links/{code}/stats?days=30` (API key required). Counts are approximate under overload
/// in async mode (dropped events are counted in metrics, not here).
@RestController
public class StatsController {

    private static final int TOP_N = 10;

    private final ClickStatsRepository stats;
    private final LinkService links;
    private final Clock clock;

    public StatsController(ClickStatsRepository stats, LinkService links, Clock clock) {
        this.stats = stats;
        this.links = links;
        this.clock = clock;
    }

    @Operation(summary = "Click analytics for a link")
    @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    @GetMapping("/api/v1/links/{code}/stats")
    @RequiresApiKey(scope = ApiScopes.STATS_READ)
    public StatsResponse stats(
            @PathVariable @Pattern(regexp = Link.CODE_PATTERN) String code,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        links.get(code); // 404 for unknown codes (deleted links still have history)
        LocalDate to = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = to.minusDays(days - 1L);
        List<DailyCount> daily = stats.daily(code, from, to);
        long total = daily.stream().mapToLong(DailyCount::clicks).sum();
        return new StatsResponse(
                code,
                from,
                to,
                total,
                daily,
                stats.top(code, "referrer", from, to, TOP_N),
                stats.top(code, "browser", from, to, TOP_N));
    }
}
