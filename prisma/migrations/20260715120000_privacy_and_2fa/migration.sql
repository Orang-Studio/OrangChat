-- Privacy preferences (enforced server-side for DMs and friend requests).
ALTER TABLE "User" ADD COLUMN     "dmPrivacy" TEXT NOT NULL DEFAULT 'everyone',
ADD COLUMN     "friendRequestPrivacy" TEXT NOT NULL DEFAULT 'everyone',
ADD COLUMN     "typingIndicators" BOOLEAN NOT NULL DEFAULT true,
ADD COLUMN     "totpSecret" TEXT,
ADD COLUMN     "totpEnabled" BOOLEAN NOT NULL DEFAULT false;

-- Single-use 2FA recovery codes; only argon2 hashes are stored.
CREATE TABLE "TotpBackupCode" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "codeHash" TEXT NOT NULL,
    "usedAt" TIMESTAMP(3),

    CONSTRAINT "TotpBackupCode_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "TotpBackupCode_userId_idx" ON "TotpBackupCode"("userId");

ALTER TABLE "TotpBackupCode" ADD CONSTRAINT "TotpBackupCode_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
