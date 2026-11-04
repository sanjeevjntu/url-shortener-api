-- V1: core link storage and idempotency keys (tasks T2, T5).
-- Design notes: docs/adr/0001 (plain JDBC), 0003 (code generation), 0007 (no URL dedupe).
--   * bigint identity PK: narrow and sequential, so indexes stay compact (UUID v4 PKs bloat B-trees).
--   * The ONLY uniqueness rule is the code. Every create makes a new link, so there is no
--     "does this URL already exist?" lookup and no cross-request locking on the URL.
--   * Soft delete keeps the row, so UNIQUE(code) also guarantees a deleted code is never reused.
-- V1 has not been released; it is edited in place. After first release, schema changes are
-- forward-only (V2, V3, ...), never edits to an applied migration.

CREATE TABLE links (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(32)   NOT NULL,
    target_url  VARCHAR(4096) NOT NULL,
    is_custom   BOOLEAN       NOT NULL DEFAULT FALSE,
    status      VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ   NOT NULL,
    expires_at  TIMESTAMPTZ,
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_links_code        UNIQUE (code),
    CONSTRAINT ck_links_status      CHECK (status IN ('ACTIVE', 'DISABLED', 'EXPIRED', 'DELETED')),
    CONSTRAINT ck_links_code_format CHECK (code ~ '^[A-Za-z0-9_-]{3,32}$'),
    CONSTRAINT ck_links_expiry      CHECK (expires_at IS NULL OR expires_at > created_at)
);

-- Supports a bounded, batched expiry sweeper (only rows that can still expire are indexed).
CREATE INDEX ix_links_expiring
    ON links (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- Idempotency keys are scoped per caller (scope_hash), not global. Hashes are raw 32-byte SHA-256.
CREATE TABLE idempotency_keys (
    scope_hash   BYTEA        NOT NULL,
    idem_key     VARCHAR(128) NOT NULL,
    request_hash BYTEA        NOT NULL,
    code         VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope_hash, idem_key),
    CONSTRAINT ck_idem_scope_len   CHECK (octet_length(scope_hash) = 32),
    CONSTRAINT ck_idem_request_len CHECK (octet_length(request_hash) = 32)
);

-- Supports TTL cleanup of old keys.
CREATE INDEX ix_idempotency_created_at ON idempotency_keys (created_at);
