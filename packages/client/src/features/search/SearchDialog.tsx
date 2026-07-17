import { useEffect, useMemo, useState } from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Hash, Loader2, Search } from "lucide-react";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { Avatar } from "../../components/Avatar";
import { RichText } from "../../lib/markdown";
import { formatFullTime, formatMessageTime } from "../../lib/time";
import { useEmojiMap } from "../emojis/queries";
import { useServerDetail } from "../servers/queries";
import { searchMessages } from "./api";

interface SearchDialogProps {
  serverId: string;
  /** When set, a toggle lets the user scope results to this channel. */
  currentChannelId?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function SearchDialog({
  serverId,
  currentChannelId,
  open,
  onOpenChange,
}: SearchDialogProps) {
  const navigate = useNavigate();
  const [raw, setRaw] = useState("");
  const [query, setQuery] = useState("");
  const [thisChannel, setThisChannel] = useState(false);

  // Debounce the query so we don't hit the API on every keystroke.
  useEffect(() => {
    const t = setTimeout(() => setQuery(raw.trim()), 300);
    return () => clearTimeout(t);
  }, [raw]);

  // Reset on close.
  useEffect(() => {
    if (!open) {
      setRaw("");
      setQuery("");
      setThisChannel(false);
    }
  }, [open]);

  const emojis = useEmojiMap();
  const { data: detail } = useServerDetail(serverId);
  const channelName = useMemo(() => {
    const map = new Map<string, string>();
    detail?.channels?.forEach((c) => map.set(c.id, c.name ?? "channel"));
    return map;
  }, [detail]);

  const scopedChannel = thisChannel ? currentChannelId : undefined;
  const { data, isFetching } = useQuery({
    queryKey: ["search", serverId, query, scopedChannel ?? "all"],
    queryFn: () => searchMessages(serverId, { q: query, channelId: scopedChannel }),
    enabled: open && query.length > 0,
    placeholderData: keepPreviousData,
  });

  const results = data?.items ?? [];

  const jump = (channelId: string) => {
    onOpenChange(false);
    navigate(`/servers/${serverId}/channels/${channelId}`);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Search messages" className="max-w-lg">
        <div className="mt-3 flex items-center gap-2">
          <div className="relative flex-1">
            <Search
              aria-hidden
              className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-muted"
            />
            <input
              autoFocus
              aria-label="Search messages"
              value={raw}
              onChange={(e) => setRaw(e.target.value)}
              placeholder="Search this server…"
              className="h-11 w-full rounded-lg border border-border bg-surface-1 pl-9 pr-3 text-base text-ink placeholder:text-ink-muted hover:border-border-strong md:h-10 md:text-sm"
            />
          </div>
        </div>

        {currentChannelId && (
          <label className="mt-2 flex cursor-pointer items-center gap-2 text-sm text-ink-secondary">
            <input
              type="checkbox"
              checked={thisChannel}
              onChange={(e) => setThisChannel(e.target.checked)}
              className="accent-primary"
            />
            Only this channel
          </label>
        )}

        <div className="mt-3 min-h-[8rem] max-h-[50dvh] overflow-y-auto">
          {query.length === 0 ? (
            <p className="py-8 text-center text-sm text-ink-muted">
              Type to search messages.
            </p>
          ) : isFetching && results.length === 0 ? (
            <div className="flex justify-center py-8">
              <Loader2 aria-hidden className="size-5 animate-spin text-ink-muted" />
            </div>
          ) : results.length === 0 ? (
            <p className="py-8 text-center text-sm text-ink-muted">
              No messages found for “{query}”.
            </p>
          ) : (
            <ul className="flex flex-col gap-1">
              {results.map((m) => (
                <li key={m.id}>
                  <button
                    type="button"
                    onClick={() => jump(m.channelId)}
                    className="flex w-full gap-3 rounded-lg p-2 text-left transition-colors hover:bg-surface-3"
                  >
                    <Avatar user={m.author} className="mt-0.5 size-8 shrink-0" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-1.5 text-xs text-ink-muted">
                        <Hash aria-hidden className="size-3" />
                        <span className="truncate">
                          {channelName.get(m.channelId) ?? "channel"}
                        </span>
                        <span>·</span>
                        <span className="font-medium text-ink-secondary">
                          {m.author.displayName}
                        </span>
                        <time dateTime={m.createdAt} title={formatFullTime(m.createdAt)}>
                          {formatMessageTime(m.createdAt)}
                        </time>
                      </div>
                      <div className="mt-0.5 line-clamp-2 break-words text-sm">
                        <RichText content={m.content} emojis={emojis} />
                      </div>
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
