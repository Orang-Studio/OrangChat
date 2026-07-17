-- Discord-parity server features: role display/hierarchy metadata, server
-- settings, channel settings, member timeouts, pinned messages, audit log.
--
-- Every column is additive with a default that reproduces today's behaviour, so
-- the running binary keeps working against this schema and the migration can be
-- applied before the deploy rather than in lockstep with it.

-- ── Roles ──
-- hoist = show members with this role in their own member-list group.
-- mentionable = anyone may @mention it, not just those with MENTION_EVERYONE.
ALTER TABLE "Role" ADD COLUMN IF NOT EXISTS hoist BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Role" ADD COLUMN IF NOT EXISTS mentionable BOOLEAN NOT NULL DEFAULT false;

-- ── Server settings ──
-- systemChannelId/afkChannelId are deliberately NOT foreign keys to "Channel":
-- they are soft pointers, and a deleted channel should blank the setting rather
-- than cascade into deleting the server. The read path tolerates a dangling id.
ALTER TABLE "Server" ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE "Server" ADD COLUMN IF NOT EXISTS "bannerUrl" TEXT;
ALTER TABLE "Server" ADD COLUMN IF NOT EXISTS "systemChannelId" TEXT;
ALTER TABLE "Server" ADD COLUMN IF NOT EXISTS "afkChannelId" TEXT;
ALTER TABLE "Server" ADD COLUMN IF NOT EXISTS "afkTimeout" INTEGER NOT NULL DEFAULT 300;
ALTER TABLE "Server" ADD COLUMN IF NOT EXISTS "defaultMessageNotifications" TEXT NOT NULL DEFAULT 'all';

-- ── Channels ──
-- rateLimitPerUser is slowmode in seconds; 0 = off, which is what every existing
-- channel has been doing implicitly.
ALTER TABLE "Channel" ADD COLUMN IF NOT EXISTS nsfw BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Channel" ADD COLUMN IF NOT EXISTS "rateLimitPerUser" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "Channel" ADD COLUMN IF NOT EXISTS "userLimit" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "Channel" ADD COLUMN IF NOT EXISTS bitrate INTEGER NOT NULL DEFAULT 64000;

-- ── Member timeouts ──
-- Null = not timed out. A past timestamp is equivalent and is left to expire on
-- its own rather than being swept, so a timeout needs no scheduled job.
ALTER TABLE "ServerMember" ADD COLUMN IF NOT EXISTS "timedOutUntil" TIMESTAMP(3);

-- ── Pinned messages ──
ALTER TABLE "Message" ADD COLUMN IF NOT EXISTS pinned BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Message" ADD COLUMN IF NOT EXISTS "pinnedAt" TIMESTAMP(3);
-- Partial index: pin lookups always filter pinned = true, and pinned messages are
-- a tiny fraction of the table.
CREATE INDEX IF NOT EXISTS "Message_channelId_pinned_idx"
  ON "Message" ("channelId", "pinnedAt" DESC) WHERE pinned;

-- ── Audit log ──
CREATE TABLE IF NOT EXISTS "AuditLog" (
  "id" TEXT NOT NULL,
  "serverId" TEXT NOT NULL,
  -- Kept even if the actor's account is deleted: an audit trail that erases its
  -- own entries when someone leaves is not an audit trail. Hence SET NULL, not
  -- CASCADE, and a nullable column.
  "actorId" TEXT,
  "action" TEXT NOT NULL,
  "targetId" TEXT,
  "targetType" TEXT,
  "changes" JSONB NOT NULL DEFAULT '{}',
  "reason" TEXT,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "AuditLog_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "AuditLog_serverId_createdAt_idx" ON "AuditLog" ("serverId", "createdAt" DESC);
CREATE INDEX IF NOT EXISTS "AuditLog_actorId_idx" ON "AuditLog" ("actorId");

DO $$ BEGIN
  ALTER TABLE "AuditLog" ADD CONSTRAINT "AuditLog_serverId_fkey"
    FOREIGN KEY ("serverId") REFERENCES "Server"("id") ON DELETE CASCADE ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE "AuditLog" ADD CONSTRAINT "AuditLog_actorId_fkey"
    FOREIGN KEY ("actorId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
