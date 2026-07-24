-- Community theme marketplace.
--
-- A theme is a map of allow-listed --oc-* CSS variables to colour values, stored
-- as JSON. There is deliberately no freeform-CSS or code column: values are
-- validated as colours in services::theme before they land here, so an installed
-- theme can only recolour the app - never inject script, fonts, or layout that
-- could overlay a fake prompt. Authors set `submitted` to request listing; an
-- admin sets `published` to approve it into the marketplace.

CREATE TABLE IF NOT EXISTS "Theme" (
    "id"        TEXT NOT NULL,
    "authorId"  TEXT NOT NULL,
    "name"      TEXT NOT NULL,
    "vars"      JSONB NOT NULL DEFAULT '{}',
    "submitted" BOOLEAN NOT NULL DEFAULT false,
    "published" BOOLEAN NOT NULL DEFAULT false,
    "installs"  INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "Theme_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "Theme_authorId_fkey" FOREIGN KEY ("authorId")
        REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE INDEX IF NOT EXISTS "Theme_authorId_idx" ON "Theme" ("authorId");
CREATE INDEX IF NOT EXISTS "Theme_published_installs_idx" ON "Theme" ("published", "installs");
