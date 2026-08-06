-- Shared DM chat background. Nullable: null means "no custom background".
ALTER TABLE "Channel" ADD COLUMN "backgroundUrl" TEXT;
