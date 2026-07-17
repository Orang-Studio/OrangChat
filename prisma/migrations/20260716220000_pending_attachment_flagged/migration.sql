-- Image moderation records its verdict on the pending attachment at upload time.
-- The column is written unconditionally by the upload path, so it must exist even
-- where moderation is switched off: with OPENAI_API_KEY unset nothing is checked
-- and every row simply lands false.
--
-- Existing rows predate moderation and were never checked, so false is also the
-- honest default for them -- an unchecked image is not a flagged one.
ALTER TABLE "PendingAttachment" ADD COLUMN IF NOT EXISTS flagged BOOLEAN NOT NULL DEFAULT false;
