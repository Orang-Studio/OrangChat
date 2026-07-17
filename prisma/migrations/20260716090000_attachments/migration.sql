-- Staging area for uploads between the upload round trip and message:send.
-- Message.attachments is snapshotted from these rows so clients never supply
-- their own attachment JSON.
CREATE TABLE "PendingAttachment" (
    "id" TEXT NOT NULL,
    "uploaderId" TEXT NOT NULL,
    "url" TEXT NOT NULL,
    "filename" TEXT NOT NULL,
    "contentType" TEXT NOT NULL,
    "size" INTEGER NOT NULL,
    "width" INTEGER,
    "height" INTEGER,
    "storage" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "PendingAttachment_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "PendingAttachment_uploaderId_idx" ON "PendingAttachment"("uploaderId");

-- Supports the age-based sweep of uploads that were never sent.
CREATE INDEX "PendingAttachment_createdAt_idx" ON "PendingAttachment"("createdAt");

ALTER TABLE "PendingAttachment" ADD CONSTRAINT "PendingAttachment_uploaderId_fkey" FOREIGN KEY ("uploaderId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
