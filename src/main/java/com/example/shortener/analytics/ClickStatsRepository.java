package com.example.shortener.analytics;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/// Reads and writes the `link_click_stats` counters (V2 migration).
@Repository
public class ClickStatsRepository {

    private static final int JDBC_BATCH_SIZE = 500;

    private static final String UPSERT =
            """
            INSERT INTO link_click_stats (code, day, dimension, value, clicks)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (code, dimension, day, value)
            DO UPDATE SET clicks = link_click_stats.clicks + EXCLUDED.clicks
            """;

    public record DailyCount(LocalDate day, long clicks) {}

    public record NamedCount(String name, long clicks) {}

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;

    public ClickStatsRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbc) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
    }

    /// Applies increments as JDBC batches in **one transaction**: one commit (one WAL flush) per flush instead of
    /// one per row, and a failure leaves no half-written batch. Input is sorted by key (see [ClickAggregator]).
    @Transactional
    public void add(List<ClickAggregator.Increment> increments) {
        if (increments.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(UPSERT, increments, JDBC_BATCH_SIZE, (ps, inc) -> {
            ps.setString(1, inc.key().code());
            ps.setDate(2, Date.valueOf(inc.key().day()));
            ps.setString(3, inc.key().dimension());
            ps.setString(4, inc.key().value());
            ps.setLong(5, inc.clicks());
        });
    }

    public List<DailyCount> daily(String code, LocalDate from, LocalDate to) {
        return jdbc.sql(
                        """
                        SELECT day, clicks FROM link_click_stats
                        WHERE code = :code AND dimension = 'total' AND day BETWEEN :from AND :to
                        ORDER BY day
                        """)
                .param("code", code)
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, _) -> new DailyCount(rs.getDate("day").toLocalDate(), rs.getLong("clicks")))
                .list();
    }

    /// Top values of one dimension (`referrer` or `browser`) over the range.
    public List<NamedCount> top(String code, String dimension, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql(
                        """
                        SELECT value, SUM(clicks) AS clicks FROM link_click_stats
                        WHERE code = :code AND dimension = :dimension AND day BETWEEN :from AND :to
                        GROUP BY value
                        ORDER BY clicks DESC, value
                        LIMIT :limit
                        """)
                .param("code", code)
                .param("dimension", dimension)
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .param("limit", limit)
                .query((rs, _) -> new NamedCount(rs.getString("value"), rs.getLong("clicks")))
                .list();
    }
}
