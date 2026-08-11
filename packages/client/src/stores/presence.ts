import { create } from "zustand";
import type { PresenceDevice, PresenceStatus, UserActivity } from "@orangchat/shared";

export interface Presence {
  status: PresenceStatus;
  devices: PresenceDevice[];
  activities: UserActivity[];
}


export const usePresenceStore = create<Record<string, Presence>>(() => ({}));

export const presenceActions = {
  set: (userId: string, presence: Presence) =>
    usePresenceStore.setState({ [userId]: presence }),
};
