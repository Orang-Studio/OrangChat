import type { VoiceStatePayload } from "@orangchat/shared";
import { api } from "../../lib/api";

export const getVoiceParticipants = (channelId: string) =>
  api<VoiceStatePayload[]>(`/channels/${channelId}/voice`);
