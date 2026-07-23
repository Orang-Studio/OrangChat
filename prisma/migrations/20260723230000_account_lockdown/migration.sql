-- Account lockdown: a self-service freeze for when someone thinks their account
-- is compromised but isn't ready to delete it.
--
-- While set, nothing can sign in, no new DM can be opened with the account, and
-- no friend request reaches it. Existing sessions are revoked when it's turned
-- on, except the one turning it on - otherwise enabling it would lock the owner
-- out along with the intruder.
--
-- Additive and nullable, so it can be applied ahead of the deploy.

ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "lockdownAt" TIMESTAMP(3);
