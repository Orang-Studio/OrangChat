import { useEffect, useMemo, useRef, type ReactNode } from "react";
import { Loader2 } from "lucide-react";
import type { Message } from "@orangchat/shared";
import { withinGroupWindow } from "../../lib/time";
import { MessageItem } from "./MessageItem";

interface MessageListProps {
  messages: Message[];
  channelName: string;
  hasOlder: boolean;
  isLoadingOlder: boolean;
  onLoadOlder: () => void;
  selfId: string | undefined;
  canManage: boolean;
  onReply: (message: Message) => void;
  /** userId → display name, for rendering `<@id>` mentions. */
  mentionNames?: Record<string, string>;
  /** Start-of-history block, shown once there's nothing older to load.
   * Defaults to the channel welcome; DMs pass their own. */
  intro?: ReactNode;
}

/**
 * Scrollable message pane. Uses `flex-col-reverse` so the browser natively
 * pins the view to the bottom for new messages and preserves the scroll
 * position when older pages prepend - no manual scroll math.
 */
export function MessageList({
  messages,
  channelName,
  hasOlder,
  isLoadingOlder,
  onLoadOlder,
  selfId,
  canManage,
  onReply,
  mentionNames,
  intro,
}: MessageListProps) {
  const topSentinel = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);

  // Load older history when the (visual) top sentinel scrolls into view.
  useEffect(() => {
    const el = topSentinel.current;
    if (!el || !hasOlder) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) onLoadOlder();
      },
      { root: scroller.current, rootMargin: "200px" },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasOlder, onLoadOlder]);

  const byId = useMemo(() => {
    const map = new Map<string, Message>();
    for (const m of messages) map.set(m.id, m);
    return map;
  }, [messages]);

  // Chronological walk decides grouping; the DOM renders reversed.
  const rows = useMemo(
    () =>
      messages.map((message, i) => {
        const prev = messages[i - 1];
        const compact =
          !!prev &&
          prev.author.id === message.author.id &&
          !message.replyToId &&
          withinGroupWindow(prev.createdAt, message.createdAt);
        return { message, compact };
      }),
    [messages],
  );

  return (
    <div
      ref={scroller}
      className="flex flex-1 flex-col-reverse overflow-y-auto pb-2"
      role="log"
      aria-label={`Messages in ${channelName}`}
    >
      {/* column-reverse: first DOM child = visual bottom. Render newest first. */}
      {[...rows].reverse().map(({ message, compact }) => (
        <MessageItem
          key={message.id}
          message={message}
          compact={compact}
          replyTo={message.replyToId ? byId.get(message.replyToId) : undefined}
          isOwn={message.author.id === selfId}
          canManage={canManage}
          onReply={onReply}
          mentionNames={mentionNames}
          selfId={selfId}
        />
      ))}

      {hasOlder ? (
        <div ref={topSentinel} className="flex justify-center py-4">
          {isLoadingOlder && (
            <Loader2 aria-hidden className="size-5 animate-spin text-ink-muted" />
          )}
        </div>
      ) : (
        (intro ?? (
          <div className="px-4 pb-2 pt-6">
            <h2 className="text-xl font-bold">Welcome to #{channelName}</h2>
            <p className="text-sm text-ink-secondary">
              This is the start of the channel. Say something!
            </p>
          </div>
        ))
      )}
    </div>
  );
}
