-- Badge catalog moved from icon glyphs to artwork, and the slugs were renamed
-- to match. This rewrites the slugs already stored on user rows so existing
-- holders keep their badge under the new name.
--
--   early_member    -> founder
--   early_developer -> developer
--   moderator       -> (retired; no artwork, dropped)
--
-- Idempotent: array_replace/array_remove are no-ops once the old slug is gone.

UPDATE "User" SET "badges" = array_replace("badges", 'early_member', 'founder')
    WHERE "badges" @> ARRAY['early_member'];

UPDATE "User" SET "badges" = array_replace("badges", 'early_developer', 'developer')
    WHERE "badges" @> ARRAY['early_developer'];

UPDATE "User" SET "badges" = array_remove("badges", 'moderator')
    WHERE "badges" @> ARRAY['moderator'];
