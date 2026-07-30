-- Full-text search for server channel messages.
--
-- Replaces the unindexed `content ILIKE '%...%'` full scan in
-- message::search_messages.
--
-- The tsvector is a stored generated column rather than an index-only
-- expression. With an expression index the planner still recomputes
-- to_tsvector() for every row whenever it reaches those rows some other way -
-- which it does as soon as a search is narrowed to a channel and can walk
-- Message_channelId_createdAt_idx instead. That per-row recompute is dearer
-- than the ILIKE this is meant to replace, so the value is materialised once at
-- write time and both plans then read it for free.
ALTER TABLE "Message"
    ADD COLUMN IF NOT EXISTS "searchVector" tsvector
    GENERATED ALWAYS AS (to_tsvector('english', "content")) STORED;

-- Partial on `ciphertext IS NULL` because every DM is end-to-end encrypted and
-- the server holds no plaintext for them (docs/E2EE.md). Those rows can never
-- match a server-side search, so excluding them costs nothing.
CREATE INDEX IF NOT EXISTS "Message_searchVector_idx"
    ON "Message"
    USING GIN ("searchVector")
    WHERE "ciphertext" IS NULL;
