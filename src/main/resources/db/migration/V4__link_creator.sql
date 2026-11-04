-- V4: who created a link (additive; safe on databases that already ran V1-V3).

-- Creating links now requires an API key with the links:create scope. created_by is that client's name (see
-- shortener.api-keys), for audit and for cleaning up after a misused key. NULL for links created before V4, when
-- creation was public.
-- No index: finding links by creator is a rare admin query, and every insert would pay for one.
ALTER TABLE links ADD COLUMN created_by VARCHAR(64);

COMMENT ON COLUMN links.created_by IS 'Name of the API client that created the link; NULL for links created before V4.';
COMMENT ON COLUMN links.deleted_by IS 'Name of the API client that deleted the link; NULL if not deleted or deleted before V3.';
