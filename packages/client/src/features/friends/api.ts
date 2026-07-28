import type { Friend, FriendRequest } from "@orangchat/shared";
import { api } from "../../lib/api";

export interface FriendRequests {
  incoming: FriendRequest[];
  outgoing: FriendRequest[];
}

/** Sending a request either lands a new pending request or, if it matched an
 * inbound one, immediately makes you friends. */
export type SendFriendResult =
  | { accepted: true; friend: Friend }
  | { accepted: false; request: FriendRequest };

export const listFriends = () => api<Friend[]>("/friends");

export const listFriendRequests = () => api<FriendRequests>("/friends/requests");

export const sendFriendRequest = (username: string) =>
  api<SendFriendResult>("/friends/requests", {
    method: "POST",
    json: { username },
  });

/** What a scanned contact code identifies someone by (docs/E2EE.md §6.7). */
export const sendFriendRequestById = (userId: string) =>
  api<SendFriendResult>("/friends/requests", {
    method: "POST",
    json: { userId },
  });

export const acceptFriendRequest = (id: string) =>
  api<Friend>(`/friends/requests/${id}/accept`, { method: "POST" });

export const removeFriendRequest = (id: string) =>
  api<void>(`/friends/requests/${id}`, { method: "DELETE" });

export const removeFriend = (userId: string) =>
  api<void>(`/friends/${userId}`, { method: "DELETE" });
