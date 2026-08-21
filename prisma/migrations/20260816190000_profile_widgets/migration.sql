-- The profile card becomes an ordered list of widgets instead of a fixed stack
-- of sections. Position is the array index, so there is no separate ordering
-- column to keep consistent. Defaults are empty: an account that never touches
-- the editor renders the built-in order the clients already draw.
ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "profileWidgets" JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS "profileFields" JSONB NOT NULL DEFAULT '{}';

-- Credential for pushing profile field values from the owner's own service.
-- Same storage rules as "BotToken": only the SHA-256 digest is kept, so a
-- database leak hands over no live tokens.
CREATE TABLE IF NOT EXISTS "ProfileFieldToken" (
    "id"         TEXT NOT NULL,
    "userId"     TEXT NOT NULL,
    "tokenHash"  TEXT NOT NULL,
    "hint"       TEXT NOT NULL,
    "label"      TEXT NOT NULL,
    "createdAt"  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastUsedAt" TIMESTAMP(3),
    CONSTRAINT "ProfileFieldToken_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "ProfileFieldToken_tokenHash_key"
    ON "ProfileFieldToken" ("tokenHash");

CREATE INDEX IF NOT EXISTS "ProfileFieldToken_userId_idx"
    ON "ProfileFieldToken" ("userId");

ALTER TABLE "ProfileFieldToken"
    ADD CONSTRAINT "ProfileFieldToken_userId_fkey"
    FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
