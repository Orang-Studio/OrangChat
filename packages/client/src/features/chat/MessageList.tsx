import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Loader2 } from 'lucide-react';
import {
  describeSystemNotice,
  systemNoticeKind,
  type Message,
  type SystemNoticeKind,
  type User,
} from '@orangchat/shared';
import { withinGroupWindow } from '../../lib/time';
import { MessageItem } from './MessageItem';

interface MessageListProps {
  messages: Message[];
  pendingMessageIds: Set<string>;
  channelName: string;
  /** Shared DM chat background image, drawn behind the messages. */
  backgroundUrl?: string | null;
  hasOlder: boolean;
  isLoadingOlder: boolean;
  onLoadOlder: () => void;
  selfId: string | undefined;
  canManage: boolean;
  onReply: (message: Message) => void;
  /** userId → display name, for rendering `<@id>` mentions. */
  mentionNames?: Record<string, string>;
  /** username → user, for rendering `@username` mentions. */
  mentionUsers?: Record<string, { id: string; name: string }>;
  /** Start-of-history block, shown once there's nothing older to load.
   * Defaults to the channel welcome; DMs pass their own. */
  intro?: ReactNode;
  /** Message to scroll to once history is in - set from a `?m=` deep link. */
  jumpToId?: string | null;
  /** Current reply target, which receives a persistent subtle highlight. */
  replyToId?: string | null;
  /** Full users for resolved mentions, so a mention can open a profile. */
  mentionProfiles?: Record<string, User>;
  /** Called once a `jumpToId` has been acted on, so the URL can be cleaned. */
  onJumpHandled?: () => void;
}

/** How many older pages a jump will pull in before giving up on finding it. */
const JUMP_MAX_PAGES = 12;

/**
 * A system notice travels as an ordinary message because there is no
 * system-message channel to carry it, but reading it as one of the sender's
 * remarks gets it wrong - it is a fact about the conversation. So it is drawn
 * centred and unattributed to a bubble, keeping the name, since who changed
 * what is the whole point of sending it.
 */
function SystemNotice({
  message,
  kind,
  selfId,
}: {
  message: Message;
  kind: SystemNoticeKind;
  selfId: string | undefined;
}) {
  const name = message.author.id === selfId ? 'You' : message.author.displayName;
  return (
    <div className="flex justify-center px-4 py-2">
      <p role="status" className="max-w-prose text-center text-xs leading-relaxed text-ink-muted">
        <span aria-hidden>- </span>
        {describeSystemNotice(kind, name)}
        <span aria-hidden> -</span>
      </p>
    </div>
  );
}

/**
 * Scrollable message pane. Uses `flex-col-reverse` so the browser natively
 * pins the view to the bottom for new messages and preserves the scroll
 * position when older pages prepend - no manual scroll math.
 */
export function MessageList({
  messages,
  pendingMessageIds,
  channelName,
  backgroundUrl,
  hasOlder,
  isLoadingOlder,
  onLoadOlder,
  selfId,
  canManage,
  onReply,
  mentionNames,
  mentionUsers,
  intro,
  jumpToId,
  replyToId,
  mentionProfiles,
  onJumpHandled,
}: MessageListProps) {
  const topSentinel = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const [flashId, setFlashId] = useState<string | null>(null);
  const [missingJump, setMissingJump] = useState(false);
  const flashTimer = useRef<ReturnType<typeof setTimeout>>(undefined);

  // The jump loop reads these while it awaits, long after its closure was made.
  const latest = useRef({ hasOlder, isLoadingOlder, onLoadOlder });
  latest.current = { hasOlder, isLoadingOlder, onLoadOlder };

  useEffect(() => () => clearTimeout(flashTimer.current), []);

  /**
   * Scroll to a message and light it up. Anything older than the loaded window
   * isn't in the DOM yet, so pull pages in until it shows up (or history ends).
   */
  const jumpTo = useCallback(async (messageId: string) => {
    setMissingJump(false);
    for (let page = 0; page <= JUMP_MAX_PAGES; page++) {
      const target = scroller.current?.querySelector<HTMLElement>(
        `[data-message-id="${CSS.escape(messageId)}"]`,
      );
      if (target) {
        target.scrollIntoView({ block: 'center', behavior: 'smooth' });
        setFlashId(messageId);
        clearTimeout(flashTimer.current);
        flashTimer.current = setTimeout(() => setFlashId(null), 2000);
        return;
      }
      if (!latest.current.hasOlder) break;
      if (!latest.current.isLoadingOlder) latest.current.onLoadOlder();
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    // Deleted, or further back than we're willing to page through.
    setMissingJump(true);
    setTimeout(() => setMissingJump(false), 4000);
  }, []);

  // Deep link (`?m=<id>`): jump once the first page of history has rendered.
  useEffect(() => {
    if (!jumpToId || messages.length === 0) return;
    void jumpTo(jumpToId);
    onJumpHandled?.();
    // Only the id matters; re-running on every new message would yank the view.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jumpToId, messages.length > 0]);

  // Load older history when the (visual) top sentinel scrolls into view.
  useEffect(() => {
    const el = topSentinel.current;
    if (!el || !hasOlder) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) onLoadOlder();
      },
      { root: scroller.current, rootMargin: '200px' },
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
        const notice = systemNoticeKind(message.content);
        const compact =
          !!prev &&
          // A notice is a break in the conversation, not a line of it: letting
          // the message after one group into the bubble above would hide its
          // header behind a divider.
          !systemNoticeKind(prev.content) &&
          !notice &&
          prev.author.id === message.author.id &&
          !message.replyToId &&
          withinGroupWindow(prev.createdAt, message.createdAt);
        return { message, compact, notice };
      }),
    [messages],
  );

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      {backgroundUrl && (
        <img
          src={backgroundUrl}
          alt=""
          aria-hidden
          className="pointer-events-none absolute inset-0 h-full w-full object-cover"
        />
      )}
      {missingJump && (
        <p
          role="status"
          className="absolute inset-x-0 top-2 z-10 mx-auto w-fit rounded-lg border border-border bg-surface-4 px-3 py-1.5 text-xs text-ink-secondary shadow-lg"
        >
          Couldn't find that message - it may have been deleted.
        </p>
      )}
      <div
        ref={scroller}
        className="flex flex-1 flex-col-reverse overflow-y-auto pb-2"
        role="log"
        aria-label={`Messages in ${channelName}`}
      >
        {/* column-reverse: first DOM child = visual bottom. Render newest first. */}
        {[...rows]
          .reverse()
          .map(({ message, compact, notice }) =>
            notice ? (
              <SystemNotice
                key={message.clientId ?? message.id}
                message={message}
                kind={notice}
                selfId={selfId}
              />
            ) : (
              <MessageItem
                key={message.clientId ?? message.id}
                message={message}
                pending={pendingMessageIds.has(message.id)}
                compact={compact}
                replyTo={message.replyToId ? byId.get(message.replyToId) : undefined}
                isOwn={message.author.id === selfId}
                canManage={canManage}
                onReply={onReply}
                onJumpTo={(id) => void jumpTo(id)}
                flash={flashId === message.id}
                replying={replyToId === message.id}
                mentionNames={mentionNames}
                mentionUsers={mentionUsers}
                selfId={selfId}
                mentionProfiles={mentionProfiles}
              />
            ),
          )}

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
    </div>
  );
}
