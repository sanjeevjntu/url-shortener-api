package com.example.shortener.link;

import java.time.Instant;

/** Read model of a stored link. */
public record Link(
        long id,
        String code,
        String targetUrl,
        LinkStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant deletedAt) {

    /** Format of every code in the database (matches the {@code ck_links_code_format} CHECK constraint). */
    public static final String CODE_PATTERN = "[A-Za-z0-9_-]{3,32}";

    /** Lazy expiry: a row can be ACTIVE in the DB yet already past its expiry instant. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isServableAt(Instant now) {
        return status == LinkStatus.ACTIVE && !isExpiredAt(now);
    }
}
