-- Soundboard clips, per server.
--
-- Purely additive: a new table only, so the running binary is unaffected and
-- this can be applied ahead of the deploy.

CREATE TABLE IF NOT EXISTS "Sound" (
    "id"        TEXT NOT NULL,
    "serverId"  TEXT NOT NULL,
    "name"      TEXT NOT NULL,
    "url"       TEXT NOT NULL,
    "duration"  DOUBLE PRECISION NOT NULL,
    "emoji"     TEXT,
    "volume"    DOUBLE PRECISION NOT NULL DEFAULT 1,
    "creatorId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Sound_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "Sound_serverId_name_key" ON "Sound" ("serverId", "name");
CREATE INDEX IF NOT EXISTS "Sound_serverId_idx" ON "Sound" ("serverId");

-- Deleting a server takes its sounds with it; losing the uploader's account
-- must not, or the board would empty itself when someone leaves.
ALTER TABLE "Sound"
    ADD CONSTRAINT "Sound_serverId_fkey" FOREIGN KEY ("serverId")
    REFERENCES "Server" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "Sound"
    ADD CONSTRAINT "Sound_creatorId_fkey" FOREIGN KEY ("creatorId")
    REFERENCES "User" ("id") ON DELETE SET NULL ON UPDATE CASCADE;
