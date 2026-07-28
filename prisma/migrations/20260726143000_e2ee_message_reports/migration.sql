CREATE TABLE "MessageReport" (
    "id" TEXT NOT NULL,
    "messageId" TEXT NOT NULL,
    "channelId" TEXT NOT NULL,
    "reporterId" TEXT NOT NULL,
    "authorId" TEXT NOT NULL,
    "reason" TEXT,
    "plaintext" TEXT NOT NULL,
    "encrypted" BOOLEAN NOT NULL DEFAULT false,
    "ciphertext" BYTEA,
    "encEpoch" INTEGER,
    "senderDeviceId" TEXT,
    "signature" BYTEA,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "MessageReport_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "MessageReport_reporterId_fkey"
      FOREIGN KEY ("reporterId") REFERENCES "User"("id")
      ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE UNIQUE INDEX "MessageReport_reporterId_messageId_key"
    ON "MessageReport"("reporterId", "messageId");
CREATE INDEX "MessageReport_channelId_createdAt_idx"
    ON "MessageReport"("channelId", "createdAt");
CREATE INDEX "MessageReport_authorId_createdAt_idx"
    ON "MessageReport"("authorId", "createdAt");
