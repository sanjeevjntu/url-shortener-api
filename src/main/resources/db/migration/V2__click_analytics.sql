-- V2: aggregated click analytics (tasks T7, T9; ADR-0004).
-- One row per (code, dimension, day, value) holds a counter. Writers add batched increments with
-- INSERT ... ON CONFLICT DO UPDATE, so a viral link costs one upsert per flush, not one write per click.
--   dimension = 'total'    -> value = ''           (daily click count)
--   dimension = 'referrer' -> value = referrer host or 'direct'
--   dimension = 'browser'  -> value = coarse browser family
-- No raw IPs, no full referrer URLs.
-- No foreign key to links: keeps the write path free of FK checks, and codes are never reused, so history
-- cannot be attributed to the wrong link.
-- PK column order serves the reads: code + dimension + day range.

CREATE TABLE link_click_stats (
    code       VARCHAR(32)  NOT NULL,
    day        DATE         NOT NULL,
    dimension  VARCHAR(16)  NOT NULL,
    value      VARCHAR(255) NOT NULL DEFAULT '',
    clicks     BIGINT       NOT NULL,
    PRIMARY KEY (code, dimension, day, value),
    CONSTRAINT ck_click_dimension CHECK (dimension IN ('total', 'referrer', 'browser')),
    CONSTRAINT ck_click_positive  CHECK (clicks >= 0)
);
