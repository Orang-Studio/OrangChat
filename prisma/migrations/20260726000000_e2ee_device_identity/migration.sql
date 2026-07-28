ALTER TABLE "Channel"
    ADD COLUMN IF NOT EXISTS "e2ee" BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS "epochNumber" INTEGER NOT NULL DEFAULT 0;

ALTER TABLE "Message"
    ADD COLUMN IF NOT EXISTS "ciphertext" BYTEA,
    ADD COLUMN IF NOT EXISTS "encEpoch" INTEGER,
    ADD COLUMN IF NOT EXISTS "encVersion" INTEGER;

CREATE TABLE IF NOT EXISTS "Device" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "platform" TEXT NOT NULL,
    "ikSigPub" BYTEA NOT NULL,
    "ikDhPub" BYTEA NOT NULL,
    "bundleSig" BYTEA NOT NULL,
    "authorizedBy" TEXT,
    "authorizationSig" BYTEA,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "revokedAt" TIMESTAMP(3),
    CONSTRAINT "Device_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "Device_userId_idx" ON "Device" ("userId");

CREATE TABLE IF NOT EXISTS "DeviceLogEntry" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "seq" INTEGER NOT NULL,
    "kind" TEXT NOT NULL,
    "payload" BYTEA NOT NULL,
    "entryHash" BYTEA NOT NULL,
    "prevHash" BYTEA,
    "signature" BYTEA NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DeviceLogEntry_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "DeviceLogEntry_userId_seq_key" ON "DeviceLogEntry" ("userId", "seq");
CREATE INDEX IF NOT EXISTS "DeviceLogEntry_userId_idx" ON "DeviceLogEntry" ("userId");

CREATE TABLE IF NOT EXISTS "ChannelEpoch" (
    "id" TEXT NOT NULL,
    "channelId" TEXT NOT NULL,
    "epoch" INTEGER NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdBy" TEXT NOT NULL,
    CONSTRAINT "ChannelEpoch_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "ChannelEpoch_channelId_epoch_key" ON "ChannelEpoch" ("channelId", "epoch");

CREATE TABLE IF NOT EXISTS "KeyEnvelope" (
    "id" TEXT NOT NULL,
    "epochId" TEXT NOT NULL,
    "deviceId" TEXT NOT NULL,
    "ephemeralPub" BYTEA NOT NULL,
    "wrapNonce" BYTEA NOT NULL,
    "wrapped" BYTEA NOT NULL,
    CONSTRAINT "KeyEnvelope_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "KeyEnvelope_epochId_deviceId_key" ON "KeyEnvelope" ("epochId", "deviceId");
CREATE INDEX IF NOT EXISTS "KeyEnvelope_deviceId_idx" ON "KeyEnvelope" ("deviceId");

ALTER TABLE "Device"
    ADD CONSTRAINT "Device_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "DeviceLogEntry"
    ADD CONSTRAINT "DeviceLogEntry_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "ChannelEpoch"
    ADD CONSTRAINT "ChannelEpoch_channelId_fkey" FOREIGN KEY ("channelId") REFERENCES "Channel" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "KeyEnvelope"
    ADD CONSTRAINT "KeyEnvelope_epochId_fkey" FOREIGN KEY ("epochId") REFERENCES "ChannelEpoch" ("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "KeyEnvelope"
    ADD CONSTRAINT "KeyEnvelope_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "Device" ("id") ON DELETE CASCADE ON UPDATE CASCADE;
