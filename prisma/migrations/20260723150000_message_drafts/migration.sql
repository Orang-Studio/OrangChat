-- Per-user, per-channel message drafts, so an unsent message survives leaving
-- the channel or switching devices.
--
-- Additive: a new table only, so it can be applied ahead of the deploy.

CREATE TABLE IF NOT EXISTS "Draft" (
    "userId"    TEXT NOT NULL,
    "channelId" TEXT NOT NULL,
    "content"   TEXT NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Draft_pkey" PRIMARY KEY ("userId", "channelId")
);

CREATE INDEX IF NOT EXISTS "Draft_userId_idx" ON "Draft" ("userId");

ALTER TABLE "Draft"
    ADD CONSTRAINT "Draft_userId_fkey" FOREIGN KEY ("userId")
    REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "Draft"
    ADD CONSTRAINT "Draft_channelId_fkey" FOREIGN KEY ("channelId")
    REFERENCES "Channel" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
