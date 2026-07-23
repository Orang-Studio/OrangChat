-- Account deletion as a tombstone rather than a row delete.
--
-- Message.authorId is ON DELETE CASCADE, so actually deleting the row would take
-- the user's whole message history out of conversations other people are still
-- reading. Instead the row survives with everything identifying scrubbed, and
-- this column marks it: nothing can sign in as a tombstoned account, and clients
-- render it as a deleted user.
--
-- Additive and nullable, so it can be applied ahead of the deploy.

ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "deletedAt" TIMESTAMP(3);

-- Deleted accounts are excluded from most lookups; a partial index keeps that
-- filter cheap without weighing down the far more common live-user reads.
CREATE INDEX IF NOT EXISTS "User_deletedAt_idx" ON "User" ("deletedAt")
    WHERE "deletedAt" IS NOT NULL;
