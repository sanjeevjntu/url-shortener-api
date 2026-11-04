package com.example.shortener.link;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLinkRepository implements LinkRepository {

    // Explicit column list (no SELECT *): stable under schema evolution.
    private static final String COLUMNS = "id, code, target_url, status, created_at, expires_at, deleted_at";

    private final JdbcClient jdbc;

    public JdbcLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> tryInsert(NewLink link) {
        // One round trip, race-free, and unlike exception-driven retry it never aborts the transaction.
        // CHECK violations are NOT swallowed by ON CONFLICT; they surface as DataIntegrityViolationException.
        return jdbc.sql(
                        """
                        INSERT INTO links (code, target_url, status, created_at, expires_at, created_by)
                        VALUES (:code, :targetUrl, 'ACTIVE', :createdAt, :expiresAt, :createdBy)
                        ON CONFLICT (code) DO NOTHING
                        RETURNING id
                        """)
                .param("code", link.code())
                .param("targetUrl", link.targetUrl())
                .param("createdAt", toOffset(link.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", toOffset(link.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdBy", link.createdBy())
                .query(Long.class)
                .optional();
    }

    @Override
    public Optional<Link> findByCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM links WHERE code = :code")
                .param("code", code)
                .query(JdbcLinkRepository::mapRow)
                .optional();
    }

    @Override
    public boolean markDeleted(String code, Instant now, String deletedBy) {
        int updated = jdbc.sql(
                        """
                        UPDATE links SET status = 'DELETED', deleted_at = :now, deleted_by = :deletedBy
                        WHERE code = :code AND status = 'ACTIVE'
                        """)
                .param("code", code)
                .param("now", toOffset(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("deletedBy", deletedBy)
                .update();
        return updated == 1;
    }

    private static Link mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Link(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("target_url"),
                LinkStatus.valueOf(rs.getString("status")),
                toInstant(rs, "created_at"),
                toInstant(rs, "expires_at"),
                toInstant(rs, "deleted_at"));
    }

    // pgjdbc cannot bind java.time.Instant directly; OffsetDateTime(UTC) maps cleanly to timestamptz.
    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
