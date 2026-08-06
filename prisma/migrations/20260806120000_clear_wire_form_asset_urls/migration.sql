-- Clears asset columns poisoned with this api's own wire form.
--
-- `same_origin_asset` rewrites a row's real url to `/api/media/asset/<kind>/<id>`
-- on the way out, so a client that seeds an edit form from the object the api
-- gave it and saves it back writes that route into the very column it was
-- derived from. The route then reads the column, finds itself, and 404s - which
-- every client renders as the initial-letter placeholder, so the person's
-- picture silently disappears from their profile, their messages and their
-- notifications alike.
--
-- The original url is not recoverable from the column, so the only honest
-- repair is to clear it: the placeholder becomes intentional, and re-uploading
-- a picture fixes it for good. Writes are guarded since (services/user.rs
-- `is_wire_form`, services/server.rs), so this is one-time cleanup of rows
-- written before that landed.

UPDATE "User" SET "avatarUrl" = NULL WHERE "avatarUrl" LIKE '/api/media/asset/%';
UPDATE "User" SET "bannerUrl" = NULL WHERE "bannerUrl" LIKE '/api/media/asset/%';
UPDATE "User" SET "appIconUrl" = NULL WHERE "appIconUrl" LIKE '/api/media/asset/%';
UPDATE "Server" SET "iconUrl" = NULL WHERE "iconUrl" LIKE '/api/media/asset/%';
UPDATE "Server" SET "bannerUrl" = NULL WHERE "bannerUrl" LIKE '/api/media/asset/%';
