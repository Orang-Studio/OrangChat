import type { ScheduledEvent } from "@orangchat/shared";
import { api } from "../../lib/api";

export interface EventInput {
  name: string;
  description?: string | null;
  location?: string | null;
  channelId?: string | null;
  startsAt: string;
  endsAt?: string | null;
}

export const listEvents = (serverId: string) =>
  api<ScheduledEvent[]>(`/servers/${serverId}/events`);

export const createEvent = (serverId: string, input: EventInput) =>
  api<ScheduledEvent>(`/servers/${serverId}/events`, { method: "POST", json: input });

export const updateEvent = (eventId: string, input: EventInput) =>
  api<ScheduledEvent>(`/events/${eventId}`, { method: "PATCH", json: input });

export const deleteEvent = (eventId: string) =>
  api<void>(`/events/${eventId}`, { method: "DELETE" });

export const setEventInterest = (eventId: string, interested: boolean) =>
  api<ScheduledEvent>(`/events/${eventId}/interest`, {
    method: interested ? "PUT" : "DELETE",
  });
