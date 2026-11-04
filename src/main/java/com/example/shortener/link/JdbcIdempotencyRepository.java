package com.example.shortener.link;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final JdbcClient jdbc;

    public JdbcIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredKey> find(byte[] scopeHash, String key) {
        return jdbc.sql("SELECT request_hash, code FROM idempotency_keys WHERE scope_hash = :scope AND idem_key = :key")
                .param("scope", scopeHash)
                .param("key", key)
                .query((rs, _) -> new StoredKey(rs.getBytes("request_hash"), rs.getString("code")))
                .optional();
    }

    @Override
    public boolean tryInsert(byte[] scopeHash, String key, byte[] requestHash, String code, Instant now) {
        // If a concurrent transaction holds the same key, Postgres waits for it, then DO NOTHING applies.
        int inserted = jdbc.sql(
                        """
                        INSERT INTO idempotency_keys (scope_hash, idem_key, request_hash, code, created_at)
                        VALUES (:scope, :key, :requestHash, :code, :now)
                        ON CONFLICT (scope_hash, idem_key) DO NOTHING
                        """)
                .param("scope", scopeHash)
                .param("key", key)
                .param("requestHash", requestHash)
                .param("code", code)
                .param("now", now.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return inserted == 1;
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < :cutoff")
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }
}
