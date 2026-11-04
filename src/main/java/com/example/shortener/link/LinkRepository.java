package com.example.shortener.link;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port for links. The database decides uniqueness (UNIQUE(code)); callers never
 * "check then insert". Every create produces a new link: there is no URL-level deduplication (decision D7).
 */
public interface LinkRepository {

    /**
     * Single-statement insert that lets the database arbitrate the code.
     *
     * @return the new id, or empty if the code is already taken (including by a soft-deleted link, so
     *     codes are never reused). The caller draws a new code and retries.
     * @throws org.springframework.dao.DataIntegrityViolationException for other violations (bad code
     *     format, expiry not after creation); those are bugs or invalid input, not races.
     */
    Optional<Long> tryInsert(NewLink link);

    Optional<Link> findByCode(String code);

    /**
     * Soft delete, recording who did it. Returns false if the code is unknown or the link was not ACTIVE
     * (idempotent).
     */
    boolean markDeleted(String code, Instant now, String deletedBy);
}
