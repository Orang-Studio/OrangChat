-- A user-chosen icon that replaces the OrangChat mark on their own clients:
-- browser favicon, in-app branding, and the Electron window/tray/taskbar icon.
-- Self-only, like customCss - it is never exposed on the public UserDto, so it
-- cannot become a per-viewer tracking pixel the way a profile field would.
ALTER TABLE "User"
    ADD COLUMN IF NOT EXISTS "appIconUrl" TEXT;
