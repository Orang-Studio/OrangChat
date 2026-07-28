-- The escape hatch for an account whose every keyed device is gone. Slow on
-- purpose: the wait is what gives the real owner a chance to answer.
CREATE TABLE "KeyDeletionRequest" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "requestedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "executeAfter" TIMESTAMP(3) NOT NULL,
    "cancelTokenHash" TEXT NOT NULL,
    "requestedIp" TEXT,
    "cancelledAt" TIMESTAMP(3),
    "abortedAt" TIMESTAMP(3),
    "executedAt" TIMESTAMP(3),

    CONSTRAINT "KeyDeletionRequest_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "KeyDeletionRequest_cancelTokenHash_key" ON "KeyDeletionRequest"("cancelTokenHash");
CREATE INDEX "KeyDeletionRequest_userId_idx" ON "KeyDeletionRequest"("userId");
CREATE INDEX "KeyDeletionRequest_executeAfter_idx" ON "KeyDeletionRequest"("executeAfter");

-- One request in flight per account. A second one while the first is still
-- pending would let an attacker file repeatedly to blur which notification the
-- owner is meant to act on.
CREATE UNIQUE INDEX "KeyDeletionRequest_pending_key" ON "KeyDeletionRequest"("userId")
    WHERE "cancelledAt" IS NULL AND "abortedAt" IS NULL AND "executedAt" IS NULL;

ALTER TABLE "KeyDeletionRequest" ADD CONSTRAINT "KeyDeletionRequest_userId_fkey"
    FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
