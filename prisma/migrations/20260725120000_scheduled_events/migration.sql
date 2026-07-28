CREATE TABLE IF NOT EXISTS "ScheduledEvent" (
    "id"          TEXT NOT NULL,
    "serverId"    TEXT NOT NULL,
    "channelId"   TEXT,
    "creatorId"   TEXT,
    "name"        TEXT NOT NULL,
    "description" TEXT,
    "location"    TEXT,
    "startsAt"    TIMESTAMP(3) NOT NULL,
    "endsAt"      TIMESTAMP(3),
    "createdAt"   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"   TIMESTAMP(3) NOT NULL,
    CONSTRAINT "ScheduledEvent_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "ScheduledEvent_serverId_fkey" FOREIGN KEY ("serverId")
        REFERENCES "Server" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "ScheduledEvent_channelId_fkey" FOREIGN KEY ("channelId")
        REFERENCES "Channel" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "ScheduledEvent_creatorId_fkey" FOREIGN KEY ("creatorId")
        REFERENCES "User" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

CREATE INDEX IF NOT EXISTS "ScheduledEvent_serverId_startsAt_idx"
    ON "ScheduledEvent" ("serverId", "startsAt");

CREATE TABLE IF NOT EXISTS "EventInterest" (
    "eventId"   TEXT NOT NULL,
    "userId"    TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "EventInterest_pkey" PRIMARY KEY ("eventId", "userId"),
    CONSTRAINT "EventInterest_eventId_fkey" FOREIGN KEY ("eventId")
        REFERENCES "ScheduledEvent" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "EventInterest_userId_fkey" FOREIGN KEY ("userId")
        REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE INDEX IF NOT EXISTS "EventInterest_userId_idx" ON "EventInterest" ("userId");
