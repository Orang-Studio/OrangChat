import { useState } from "react";
import { Link, useMatch } from "react-router-dom";
import { Plus } from "lucide-react";
import type { Server } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { LogoMark } from "../../components/LogoMark";
import { Tooltip } from "../../components/ui/Tooltip";
import { UnreadBadge } from "../../components/UnreadBadge";
import { useDmUnreadTotal, useServerUnread } from "../../stores/unread";
import { useServers } from "./queries";
import { initials } from "./ServerIcon";
import { AddServerDialog } from "./AddServerDialog";

function ServerRailIcon({ server, active }: { server: Server; active: boolean }) {
  const { unread, mentionCount } = useServerUnread(server.id);
  const showUnread = unread && !active;

  return (
    <Tooltip label={server.name}>
      <Link
        to={`/servers/${server.id}`}
        aria-current={active ? "page" : undefined}
        className="group relative flex justify-center"
      >
        {/* Active / hover / unread accent bar on the rail edge */}
        <span
          className={cn(
            "absolute -left-3 top-1/2 w-[3px] -translate-y-1/2 bg-primary transition-all",
            active ? "h-9" : showUnread ? "h-2.5 bg-ink" : "h-0 group-hover:h-5",
          )}
        />
        {mentionCount > 0 && !active && (
          <span className="absolute -bottom-0.5 -right-0.5 z-10 min-w-[1.1rem] rounded-full border-2 border-surface-0 bg-danger px-1 py-0.5 text-center text-[10px] font-bold leading-none text-white">
            {mentionCount > 99 ? "99+" : mentionCount}
          </span>
        )}
        {server.iconUrl ? (
          <img
            src={server.iconUrl}
            alt={server.name}
            className={cn(
              "size-12 rounded-squircle object-cover ring-2 transition-all",
              active ? "ring-primary" : "ring-transparent group-hover:ring-border-strong",
            )}
          />
        ) : (
          <span
            className={cn(
              "flex size-12 items-center justify-center rounded-squircle font-semibold transition-colors",
              active
                ? "bg-primary text-ink-on-primary"
                : "bg-surface-3 text-ink-secondary group-hover:bg-surface-4 group-hover:text-ink",
            )}
          >
            {initials(server.name)}
          </span>
        )}
      </Link>
    </Tooltip>
  );
}

/** Far-left column: home, one icon per joined server, and the add button. */
export function ServerRail() {
  // Layout-level component: child-route params aren't visible via useParams.
  const serverId = useMatch("/servers/:serverId/*")?.params.serverId;
  const { data: servers } = useServers();
  const dmUnread = useDmUnreadTotal();
  const [addOpen, setAddOpen] = useState(false);

  return (
    <nav
      aria-label="Servers"
      className="flex w-[72px] shrink-0 flex-col items-center gap-2 overflow-y-auto bg-surface-0 px-3 py-3"
    >
      <Tooltip label="Home">
        <Link to="/" className="relative flex justify-center">
          <LogoMark className="size-12 transition-transform hover:scale-105" />
          <UnreadBadge
            count={dmUnread}
            label="unread direct messages"
            className="absolute -bottom-0.5 -right-0.5 z-10 rounded-full border-2 border-surface-0"
          />
        </Link>
      </Tooltip>
      <div className="my-1 h-px w-8 shrink-0 rounded bg-border" />

      {servers?.map((server) => (
        <ServerRailIcon key={server.id} server={server} active={server.id === serverId} />
      ))}

      <Tooltip label="Add a server">
        <button
          type="button"
          onClick={() => setAddOpen(true)}
          className="flex size-12 shrink-0 items-center justify-center rounded-squircle bg-surface-3 text-success transition-colors hover:bg-success hover:text-surface-0"
        >
          <Plus aria-hidden className="size-6" />
          <span className="sr-only">Add a server</span>
        </button>
      </Tooltip>

      <AddServerDialog open={addOpen} onOpenChange={setAddOpen} />
    </nav>
  );
}
