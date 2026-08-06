-- System notices stop being "a message whose text we recognise" and become a
-- server-set field. Recognising them by text meant anyone could forge one by
-- typing the sentence; this column is only ever written by the server, from an
-- action it carried out itself.
ALTER TABLE "Message" ADD COLUMN "systemNotice" TEXT;

-- What a notice needs beyond its own name. Only the kinds that are a live card
-- use it: a call keeps who is on it and how long it ran here, and the server
-- rewrites the row as the call progresses.
ALTER TABLE "Message" ADD COLUMN "systemData" JSONB;

-- Per-conversation "verify before messaging" override. Null follows the user's
-- global e2eeStrict. Held server-side so that changing it is a server action,
-- which is what lets the server author the notice announcing it.
ALTER TABLE "ChannelParticipant" ADD COLUMN "e2eeStrict" BOOLEAN;

-- Notices already sent are ordinary messages whose text the clients matched.
-- Convert them once, here, so history keeps rendering as notices after the
-- clients stop looking at content. Exact matches only - the same strings the
-- clients used.
UPDATE "Message" SET "systemNotice" = CASE btrim("content")
  WHEN 'Turned off the requirement to verify before messaging in this conversation.' THEN 'strictDisabled'
  WHEN 'Turned on the requirement to verify before messaging in this conversation.'  THEN 'strictEnabled'
  WHEN 'Started a new encryption key for this conversation.'                          THEN 'keyReset'
  WHEN 'Changed the chat background.'                                                 THEN 'backgroundChanged'
  WHEN 'Removed the chat background.'                                                 THEN 'backgroundRemoved'
END
WHERE btrim("content") IN (
  'Turned off the requirement to verify before messaging in this conversation.',
  'Turned on the requirement to verify before messaging in this conversation.',
  'Started a new encryption key for this conversation.',
  'Changed the chat background.',
  'Removed the chat background.'
);
