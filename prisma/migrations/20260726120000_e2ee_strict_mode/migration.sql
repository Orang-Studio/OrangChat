-- docs/E2EE.md §6.5: strict mode is a per-user policy, off by default, and it
-- only ever gates this account's own sending.
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "e2eeStrict" BOOLEAN NOT NULL DEFAULT false;
