-- V3: delete audit and tuning (additive; safe on databases that already ran V1/V2).

-- Who deleted a link: the name of the API client (see shortener.api-keys). NULL for rows deleted before V3.
ALTER TABLE links ADD COLUMN deleted_by VARCHAR(64);

-- The background expiry sweeper was removed: expiry is enforced on every read from expires_at (lazy), so this
-- index only cost write overhead.
DROP INDEX IF EXISTS ix_links_expiring;

-- Click counters are updated constantly. Leaving 10% free space per page lets PostgreSQL update a counter row
-- in place (a HOT update) instead of writing a new row version plus index entries. Applies to new pages.
ALTER TABLE link_click_stats SET (fillfactor = 90);

COMMENT ON COLUMN links.status IS
    'Written by the app: ACTIVE, DELETED. EXPIRED and DISABLED are reserved (expiry is evaluated lazily from expires_at; DISABLED is for future admin takedowns).';
COMMENT ON COLUMN links.is_custom IS 'Always false since custom aliases were removed; kept for existing rows.';
