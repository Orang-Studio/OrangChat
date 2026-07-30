-- Per-user control over which friend events raise a notification.
--
-- Requests and accepts default on: they are direct, infrequent, and always
-- about the user personally. Coming-online defaults off - it fires for every
-- friend every time they open the app, so it is the one that becomes noise
-- first and has to be asked for rather than assumed.
ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "notifyFriendRequests" BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS "notifyFriendAccepted" BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS "notifyFriendOnline"   BOOLEAN NOT NULL DEFAULT false;
