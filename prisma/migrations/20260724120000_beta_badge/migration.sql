-- The "beta" badge replaces "founder" as the early-member award: the app is
-- still in beta, so for now every existing account earns it and the retired
-- founder badge is stripped from everyone.
--
-- Idempotent: array_append is guarded so a rerun won't duplicate "beta", and
-- array_remove is a no-op once "founder" is gone.

UPDATE "User" SET "badges" = array_remove("badges", 'founder')
    WHERE "badges" @> ARRAY['founder'];

UPDATE "User" SET "badges" = array_append("badges", 'beta')
    WHERE NOT ("badges" @> ARRAY['beta']);
