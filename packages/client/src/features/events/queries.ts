import { useQuery, type QueryClient } from "@tanstack/react-query";
import type { ScheduledEvent } from "@orangchat/shared";
import { listEvents } from "./api";

export const eventKeys = {
  server: (serverId: string) => ["events", serverId] as const,
};

export function useEvents(serverId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: eventKeys.server(serverId!),
    queryFn: () => listEvents(serverId!),
    enabled: !!serverId && enabled,
  });
}

export function upsertEvent(client: QueryClient, event: ScheduledEvent): void {
  client.setQueryData<ScheduledEvent[]>(eventKeys.server(event.serverId), (list) => {
    const next = (list ?? []).filter((e) => e.id !== event.id).concat(event);
    return next.sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  });
}

/**
 * Apply a server broadcast. `interested` on the wire is whoever triggered the
 * change, so the viewer's own flag is kept from cache rather than overwritten.
 */
export function applyEventBroadcast(client: QueryClient, event: ScheduledEvent): void {
  client.setQueryData<ScheduledEvent[]>(eventKeys.server(event.serverId), (list) => {
    const existing = (list ?? []).find((e) => e.id === event.id);
    const merged = { ...event, interested: existing?.interested ?? false };
    return (list ?? [])
      .filter((e) => e.id !== event.id)
      .concat(merged)
      .sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  });
}

export function setEventInterestCount(
  client: QueryClient,
  serverId: string,
  eventId: string,
  interestedCount: number,
): void {
  client.setQueryData<ScheduledEvent[]>(eventKeys.server(serverId), (list) =>
    (list ?? []).map((e) => (e.id === eventId ? { ...e, interestedCount } : e)),
  );
}

export function removeEvent(client: QueryClient, serverId: string, eventId: string): void {
  client.setQueryData<ScheduledEvent[]>(eventKeys.server(serverId), (list) =>
    (list ?? []).filter((e) => e.id !== eventId),
  );
}
