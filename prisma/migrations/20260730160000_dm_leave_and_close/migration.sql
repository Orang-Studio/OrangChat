-- Lets a user get a DM out of their list.
--
-- Group DMs are left outright - the ChannelParticipant row is deleted, which
-- the existing membership checks already read as "not in this conversation".
--
-- A one-to-one DM cannot work that way. Deleting that row would orphan the
-- history behind a channel the user is no longer a member of, and the next
-- message from the other side would arrive at a channel they can no longer see,
-- so the conversation would silently stop working rather than reappear. Closing
-- one is therefore a per-user flag: the membership survives, the row just stops
-- being listed until something new arrives in it.
ALTER TABLE "ChannelParticipant"
    ADD COLUMN IF NOT EXISTS "hiddenAt" TIMESTAMP(3);
