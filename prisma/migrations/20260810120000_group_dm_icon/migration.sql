-- Group DM icon, set by any participant. Nullable: null means "no icon", and
-- the clients fall back to the stacked-participant glyph they drew before.
ALTER TABLE "Channel" ADD COLUMN "iconUrl" TEXT;
