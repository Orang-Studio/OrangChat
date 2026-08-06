import { create } from "zustand";
import type { PresenceDevice, PresenceStatus, UserActivity } from "@orangchat/shared";

export interface Presence {
  status: PresenceStatus;
  devices: PresenceDevice[];
  activities: UserActivity[];
}

/** Live presence by user id, for anything holding a user object that never refetches. */
export const usePresenceStore = create<Record<string, Presence>>(() => ({}));

export const presenceActions = {
  set: (userId: string, presence: Presence) =>
    usePresenceStore.setState({ [userId]: presence }),
};
