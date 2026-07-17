import { useQuery, type QueryClient } from "@tanstack/react-query";
import type { Friend, FriendRequest, User } from "@orangchat/shared";
import { listFriends, listFriendRequests, type FriendRequests } from "./api";

export const friendKeys = {
  list: ["friends"] as const,
  requests: ["friends", "requests"] as const,
};

export function useFriends() {
  return useQuery({ queryKey: friendKeys.list, queryFn: listFriends });
}

export function useFriendRequests() {
  return useQuery({ queryKey: friendKeys.requests, queryFn: listFriendRequests });
}

const sortFriends = (list: Friend[]) =>
  [...list].sort((a, b) => a.user.displayName.localeCompare(b.user.displayName));

export function addFriend(client: QueryClient, friend: Friend): void {
  client.setQueryData<Friend[]>(friendKeys.list, (list) =>
    sortFriends([...(list ?? []).filter((f) => f.user.id !== friend.user.id), friend]),
  );
}

export function removeFriendFromCache(client: QueryClient, userId: string): void {
  client.setQueryData<Friend[]>(friendKeys.list, (list) =>
    list?.filter((f) => f.user.id !== userId),
  );
}

/**
 * A friend edited their profile. `status` is presence, which rides its own
 * events and is not part of the edited profile, so the cached one is kept.
 * Re-sorts because the list is ordered by display name.
 */
export function updateFriendUser(client: QueryClient, user: User): void {
  client.setQueryData<Friend[]>(friendKeys.list, (list) => {
    if (!list?.some((f) => f.user.id === user.id)) return list;
    return sortFriends(
      list.map((f) =>
        f.user.id === user.id ? { ...f, user: { ...user, status: f.user.status } } : f,
      ),
    );
  });
}

export function addRequest(client: QueryClient, request: FriendRequest): void {
  client.setQueryData<FriendRequests>(friendKeys.requests, (reqs) => {
    if (!reqs) return reqs;
    const key = request.direction === "incoming" ? "incoming" : "outgoing";
    const others = reqs[key].filter((r) => r.id !== request.id);
    return { ...reqs, [key]: [request, ...others] };
  });
}

export function removeRequest(client: QueryClient, id: string): void {
  client.setQueryData<FriendRequests>(friendKeys.requests, (reqs) =>
    reqs
      ? {
          incoming: reqs.incoming.filter((r) => r.id !== id),
          outgoing: reqs.outgoing.filter((r) => r.id !== id),
        }
      : reqs,
  );
}

/** Drop any pending request (either direction) involving `userId`. */
export function removeRequestByUser(client: QueryClient, userId: string): void {
  client.setQueryData<FriendRequests>(friendKeys.requests, (reqs) =>
    reqs
      ? {
          incoming: reqs.incoming.filter((r) => r.user.id !== userId),
          outgoing: reqs.outgoing.filter((r) => r.user.id !== userId),
        }
      : reqs,
  );
}
