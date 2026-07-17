-- Per-server custom emoji.
--
-- Purely additive: a new table with no changes to existing ones, so the running
-- binary is unaffected and this can be applied ahead of the deploy.

CREATE TABLE IF NOT EXISTS "Emoji" (
    "id"        TEXT NOT NULL,
    "serverId"  TEXT NOT NULL,
    "name"      TEXT NOT NULL,
    "url"       TEXT NOT NULL,
    "animated"  BOOLEAN NOT NULL DEFAULT false,
    "creatorId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Emoji_pkey" PRIMARY KEY ("id")
);

-- Messages reference emoji by id, so a name is only a handle within one server.
CREATE UNIQUE INDEX IF NOT EXISTS "Emoji_serverId_name_key" ON "Emoji" ("serverId", "name");
CREATE INDEX IF NOT EXISTS "Emoji_serverId_idx" ON "Emoji" ("serverId");

-- Deleting a server takes its emoji with it; deleting the uploader's account
-- must not, or the emoji would vanish from every message it appears in.
ALTER TABLE "Emoji"
    ADD CONSTRAINT "Emoji_serverId_fkey" FOREIGN KEY ("serverId")
    REFERENCES "Server" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "Emoji"
    ADD CONSTRAINT "Emoji_creatorId_fkey" FOREIGN KEY ("creatorId")
    REFERENCES "User" ("id") ON DELETE SET NULL ON UPDATE CASCADE;
