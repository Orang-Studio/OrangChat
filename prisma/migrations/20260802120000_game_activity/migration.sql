-- "Display the game you're playing". Off by default, unlike the other presence
-- settings: detecting a game means the desktop client enumerating the running
-- processes on the machine, and that must be something the user turned on rather
-- than something that started happening because an update shipped.
ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "gameActivity" BOOLEAN NOT NULL DEFAULT false;
