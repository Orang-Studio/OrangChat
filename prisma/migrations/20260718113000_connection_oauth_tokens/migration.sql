ALTER TABLE "Connection"
ADD COLUMN "oauthAccessToken" TEXT,
ADD COLUMN "oauthRefreshToken" TEXT,
ADD COLUMN "oauthExpiresAt" TIMESTAMP(3),
ADD COLUMN "oauthScope" TEXT;
