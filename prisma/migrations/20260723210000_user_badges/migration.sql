-- Profile badges (early developer, early member, bonfire), stored as catalog
-- slugs directly on the user rather than in a join table: there are a handful of
-- them, they change rarely, and every profile read already does `SELECT * FROM
-- "User"` - so this keeps badges free at read time and the DTO mappers sync.
--
-- Additive with a default, so it can be applied ahead of the deploy.

ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "badges" TEXT[] NOT NULL DEFAULT '{}';

-- Backfill: everyone who already had an account when badges shipped is an early
-- member by definition.
UPDATE "User" SET "badges" = ARRAY['early_member'] WHERE "badges" = '{}';
