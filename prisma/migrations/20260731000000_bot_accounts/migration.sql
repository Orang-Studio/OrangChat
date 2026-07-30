-- Bot accounts. A bot is a User row, not a separate entity: authorship,
-- membership, roles and permissions all key on "User", and a parallel table
-- would need a nullable column on every one of them.
ALTER TABLE "User" ADD COLUMN "isBot" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "User" ADD COLUMN "ownerId" TEXT;

ALTER TABLE "User" ADD CONSTRAINT "User_ownerId_fkey"
  FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Only bots have an owner, and every bot has one. Enforced here rather than in
-- application code so no path can create a half-formed bot.
ALTER TABLE "User" ADD CONSTRAINT "User_bot_has_owner"
  CHECK (("isBot" = false AND "ownerId" IS NULL) OR ("isBot" = true AND "ownerId" IS NOT NULL));

CREATE INDEX "User_ownerId_idx" ON "User"("ownerId");

-- Bot API credentials, stored as SHA-256 hex. See the schema comment for why
-- this is not argon2 like every other secret in this database.
CREATE TABLE "BotToken" (
    "id" TEXT NOT NULL,
    "botId" TEXT NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "hint" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastUsedAt" TIMESTAMP(3),
    CONSTRAINT "BotToken_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "BotToken_tokenHash_key" ON "BotToken"("tokenHash");
CREATE INDEX "BotToken_botId_idx" ON "BotToken"("botId");

ALTER TABLE "BotToken" ADD CONSTRAINT "BotToken_botId_fkey"
  FOREIGN KEY ("botId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
