import { useState } from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import type { AuditLogEntry, Server } from "@orangchat/shared";
import { Loader2 } from "lucide-react";

import { Avatar } from "../../components/Avatar";
import { Button } from "../../components/ui/Button";
import { getAuditLog } from "./api";

/**
 * Every action the server records, in the order it recorded them.
 *
 * The wording is deliberately plain rather than templated per action: the log
 * is read when something has gone wrong and someone is trying to work out who
 * did it, so an unfamiliar action name shown verbatim is more useful than a
 * pretty sentence that quietly omits the ones nobody wrote a phrasing for.
 */
const ACTION_LABELS: Record<string, string> = {
  "server.update": "updated the server",
  "channel.create": "created a channel",
  "channel.update": "updated a channel",
  "channel.delete": "deleted a channel",
  "role.create": "created a role",
  "role.update": "updated a role",
  "role.delete": "deleted a role",
  "member.kick": "kicked a member",
  "member.ban": "banned a member",
  "member.unban": "unbanned a member",
  "member.timeout": "timed out a member",
  "member.role_update": "changed a member's roles",
};

export function AuditLogTab({ server }: { server: Server }) {
  const [offset, setOffset] = useState(0);

  const { data, isPending, isError, error, isFetching } = useQuery({
    queryKey: ["auditLog", server.id, offset],
    queryFn: () => getAuditLog(server.id, offset),
    placeholderData: keepPreviousData,
  });

  if (isPending) {
    return (
      <div className="flex justify-center py-10">
        <Loader2 aria-hidden className="size-5 animate-spin text-ink-muted" />
      </div>
    );
  }

  if (isError) {
    return (
      <p role="alert" className="py-8 text-center text-sm text-danger">
        {error.message}
      </p>
    );
  }

  if (data.items.length === 0) {
    return (
      <p className="py-10 text-center text-sm text-ink-muted">
        {offset === 0 ? "Nothing has been logged yet." : "No more entries."}
      </p>
    );
  }

  return (
    <div className="space-y-1">
      <ul className="divide-y divide-border">
        {data.items.map((entry) => (
          <AuditRow key={entry.id} entry={entry} />
        ))}
      </ul>
      <div className="flex items-center justify-between pt-3">
        <Button
          variant="secondary"
          size="sm"
          disabled={offset === 0 || isFetching}
          onClick={() => setOffset((o) => Math.max(0, o - 50))}
        >
          Newer
        </Button>
        <Button
          variant="secondary"
          size="sm"
          disabled={!data.nextCursor || isFetching}
          onClick={() => setOffset((o) => o + 50)}
        >
          Older
        </Button>
      </div>
    </div>
  );
}

function AuditRow({ entry }: { entry: AuditLogEntry }) {
  const changed = Object.keys(entry.changes);
  // A deleted account leaves its entries behind on purpose - the action still
  // happened, and hiding it would make the log lie by omission.
  const actorName = entry.actor?.displayName ?? "A deleted account";

  return (
    <li className="flex gap-3 py-3">
      {entry.actor ? (
        <Avatar user={entry.actor} className="size-8 shrink-0" />
      ) : (
        <div className="size-8 shrink-0 rounded-full bg-surface-3" aria-hidden />
      )}
      <div className="min-w-0 flex-1">
        <p className="text-sm text-ink">
          <span className="font-medium">{actorName}</span>{" "}
          {ACTION_LABELS[entry.action] ?? entry.action}
        </p>
        {changed.length > 0 && (
          <p className="mt-0.5 truncate text-xs text-ink-muted">Changed: {changed.join(", ")}</p>
        )}
        {entry.reason && (
          <p className="mt-0.5 text-xs italic text-ink-secondary">“{entry.reason}”</p>
        )}
      </div>
      <time
        dateTime={entry.createdAt}
        title={new Date(entry.createdAt).toLocaleString()}
        className="shrink-0 text-xs text-ink-muted"
      >
        {new Date(entry.createdAt).toLocaleDateString()}
      </time>
    </li>
  );
}
