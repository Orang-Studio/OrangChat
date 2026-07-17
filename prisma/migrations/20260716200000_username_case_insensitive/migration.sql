-- Usernames are matched case-insensitively (adding a friend, signing up, renaming),
-- so the uniqueness they are matched against has to be case-insensitive too.
-- Without this, "Adas" could register alongside "adas" and every lookup that says
-- lower(username) = lower($1) would match two rows.
--
-- The application checks this before writing, but a check outside the database
-- loses the race between two concurrent signups; this index does not.
CREATE UNIQUE INDEX IF NOT EXISTS "User_username_lower_key" ON "User" (lower(username));
