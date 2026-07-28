import { useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CalendarPlus, Hash, MapPin, Star, Trash2, Volume2 } from "lucide-react";
import {
  Permissions,
  hasPermission,
  type Channel,
  type ScheduledEvent,
  type Server,
} from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { TextField } from "../../components/ui/TextField";
import { cn } from "../../lib/cn";
import { formatFullTime } from "../../lib/time";
import { useMyPermissions, useServerDetail } from "../servers/queries";
import { createEvent, deleteEvent, setEventInterest, type EventInput } from "./api";
import { removeEvent, upsertEvent, useEvents } from "./queries";

interface EventsDialogProps {
  server: Server;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Land straight on the form instead of the list. */
  startCreating?: boolean;
}

const pad = (n: number) => String(n).padStart(2, "0");

/** `YYYY-MM-DDTHH:mm` in local time, an hour from now, for the datetime input. */
function defaultStart(): string {
  const d = new Date(Date.now() + 60 * 60_000);
  d.setMinutes(0, 0, 0);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function EventForm({
  server,
  channels,
  onDone,
}: {
  server: Server;
  channels: Channel[];
  onDone: () => void;
}) {
  const client = useQueryClient();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [channelId, setChannelId] = useState("");
  const [location, setLocation] = useState("");
  const [startsAt, setStartsAt] = useState(defaultStart);
  const [endsAt, setEndsAt] = useState("");

  const mutation = useMutation({
    mutationFn: () => {
      const input: EventInput = {
        name: name.trim(),
        description: description.trim() || null,
        location: channelId ? null : location.trim() || null,
        channelId: channelId || null,
        startsAt: new Date(startsAt).toISOString(),
        endsAt: endsAt ? new Date(endsAt).toISOString() : null,
      };
      return createEvent(server.id, input);
    },
    onSuccess: (event) => {
      upsertEvent(client, event);
      onDone();
    },
  });

  const valid = name.trim().length > 0 && startsAt.length > 0;

  return (
    <form
      className="mt-3 space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) mutation.mutate();
      }}
    >
      <TextField
        label="Event name"
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={100}
        placeholder="Game night"
        autoFocus
      />

      <div>
        <label className="mb-1.5 block text-sm font-medium text-ink-secondary">Description</label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          maxLength={1000}
          rows={3}
          placeholder="What's happening?"
          className="w-full resize-none rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm"
        />
      </div>

      <div>
        <label className="mb-1.5 block text-sm font-medium text-ink-secondary">Where</label>
        <select
          value={channelId}
          onChange={(e) => setChannelId(e.target.value)}
          className="h-10 w-full rounded-lg border border-border bg-surface-1 px-2 text-sm"
        >
          <option value="">Somewhere else</option>
          {channels.map((channel) => (
            <option key={channel.id} value={channel.id}>
              {channel.type === "voice" ? "🔊" : "#"} {channel.name}
            </option>
          ))}
        </select>
        {!channelId && (
          <div className="mt-2">
            <TextField
              label="Location"
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              maxLength={100}
              placeholder="A link, an address, anywhere"
            />
          </div>
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <TextField
          label="Starts"
          type="datetime-local"
          value={startsAt}
          onChange={(e) => setStartsAt(e.target.value)}
        />
        <TextField
          label="Ends (optional)"
          type="datetime-local"
          value={endsAt}
          onChange={(e) => setEndsAt(e.target.value)}
        />
      </div>

      {mutation.isError && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {mutation.error.message}
        </p>
      )}

      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" loading={mutation.isPending} disabled={!valid}>
          Create event
        </Button>
      </div>
    </form>
  );
}

function EventRow({
  event,
  channels,
  canManage,
}: {
  event: ScheduledEvent;
  channels: Channel[];
  canManage: boolean;
}) {
  const client = useQueryClient();
  const channel = channels.find((c) => c.id === event.channelId);

  const interest = useMutation({
    mutationFn: () => setEventInterest(event.id, !event.interested),
    onSuccess: (updated) => upsertEvent(client, updated),
  });
  const remove = useMutation({
    mutationFn: () => deleteEvent(event.id),
    onSuccess: () => removeEvent(client, event.serverId, event.id),
  });

  const past = new Date(event.endsAt ?? event.startsAt).getTime() < Date.now();

  return (
    <li className={cn("rounded-lg border border-border bg-surface-1 p-3", past && "opacity-60")}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs font-medium uppercase tracking-wide text-primary">
            {formatFullTime(event.startsAt)}
            {event.endsAt && ` → ${formatFullTime(event.endsAt)}`}
          </p>
          <p className="truncate text-sm font-semibold">{event.name}</p>
          {(channel || event.location) && (
            <p className="mt-0.5 flex items-center gap-1.5 text-xs text-ink-muted">
              {channel ? (
                <>
                  {channel.type === "voice" ? (
                    <Volume2 aria-hidden className="size-3.5" />
                  ) : (
                    <Hash aria-hidden className="size-3.5" />
                  )}
                  {channel.name}
                </>
              ) : (
                <>
                  <MapPin aria-hidden className="size-3.5" />
                  {event.location}
                </>
              )}
            </p>
          )}
        </div>
        {canManage && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="text-danger hover:text-danger"
            loading={remove.isPending}
            onClick={() => remove.mutate()}
            aria-label={`Delete ${event.name}`}
          >
            <Trash2 aria-hidden className="size-4" />
          </Button>
        )}
      </div>

      {event.description && (
        <p className="mt-2 whitespace-pre-wrap break-words text-sm text-ink-secondary">
          {event.description}
        </p>
      )}

      <div className="mt-2.5 flex items-center gap-2">
        <Button
          type="button"
          size="sm"
          variant={event.interested ? "primary" : "secondary"}
          loading={interest.isPending}
          onClick={() => interest.mutate()}
        >
          <Star aria-hidden className="size-4" />
          {event.interested ? "Interested" : "I'm interested"}
        </Button>
        <span className="text-xs text-ink-muted">{event.interestedCount} interested</span>
      </div>
    </li>
  );
}

export function EventsDialog({
  server,
  open,
  onOpenChange,
  startCreating = false,
}: EventsDialogProps) {
  const [creating, setCreating] = useState(startCreating);
  const { data: events, isLoading } = useEvents(server.id, open);
  const { data: detail } = useServerDetail(open ? server.id : undefined);
  const { data: perms } = useMyPermissions(open ? server.id : undefined);
  const canManage = perms !== undefined && hasPermission(perms, Permissions.MANAGE_EVENTS);

  const channels = useMemo(
    () =>
      (detail?.channels ?? [])
        .filter((c) => c.type === "text" || c.type === "voice")
        .sort((a, b) => a.position - b.position),
    [detail],
  );

  const upcoming = events ?? [];

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next);
        if (!next) setCreating(startCreating);
      }}
    >
      <DialogContent
        title={creating ? "Create event" : `Events in ${server.name}`}
        className="max-w-lg"
      >
        {creating ? (
          <EventForm
            server={server}
            channels={channels}
            onDone={() => (startCreating ? onOpenChange(false) : setCreating(false))}
          />
        ) : (
          <div className="mt-3 space-y-3">
            {canManage && (
              <Button type="button" size="sm" onClick={() => setCreating(true)}>
                <CalendarPlus aria-hidden className="size-4" />
                Create event
              </Button>
            )}
            {isLoading ? (
              <p className="py-6 text-center text-sm text-ink-muted">Loading events…</p>
            ) : upcoming.length === 0 ? (
              <p className="py-6 text-center text-sm text-ink-muted">Nothing scheduled yet.</p>
            ) : (
              <ul className="max-h-[60vh] space-y-2 overflow-y-auto">
                {upcoming.map((event) => (
                  <EventRow
                    key={event.id}
                    event={event}
                    channels={channels}
                    canManage={canManage}
                  />
                ))}
              </ul>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
