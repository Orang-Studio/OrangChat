import type { QueryClient } from "@tanstack/react-query";
import type { Friend, PresenceStatus } from "@orangchat/shared";

import { useAuthStore } from "../../stores/auth";
import { notify } from "../../lib/notifications";
import { friendKeys } from "../friends/queries";

/**
 * Last status we saw per user, so "came online" means an actual transition.
 *
 * Without this the notification fires on the presence burst the server sends at
 * connect - every friend who was already online would be announced as having
 * just arrived, every time the app starts. A user with no entry yet is recorded
 * silently for the same reason.
 */
const lastStatus = new Map<string, PresenceStatus>();

export function maybeNotifyFriendOnline(
  client: QueryClient,
  userId: string,
  status: PresenceStatus,
): void {
  const previous = lastStatus.get(userId);
  lastStatus.set(userId, status);

  if (previous === undefined || previous !== "offline" || status === "offline") return;
  if (!useAuthStore.getState().user?.notifyFriendOnline) return;
  if (userId === useAuthStore.getState().user?.id) return;

  const friend = client
    .getQueryData<Friend[]>(friendKeys.list)
    ?.find((f) => f.user.id === userId);
  if (!friend) return;

  notify({
    title: friend.user.displayName,
    body: "is now online",
    icon: friend.user.avatarUrl ?? undefined,
    href: "/friends",
    tag: `friend-online:${userId}`,
  });
}
