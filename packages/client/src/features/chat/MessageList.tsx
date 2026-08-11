import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ArrowDown, Loader2 } from 'lucide-react';
import {
  callNotice,
  describeSystemNotice,
  isSystemNotice,
  systemNoticeKind,
  type Message,
  type User,
} from '@orangchat/shared';
import { withinGroupWindow } from '../../lib/time';
import { CallCard } from '../voice/CallCard';
import { MessageItem } from './MessageItem';
import { t } from "../../lib/i18n";

interface MessageListProps {
  messages: Message[];
  /** The conversation itself - a call card needs something to ring. */
  channel: { id: string; name: string | null; serverId: string | null };
  pendingMessageIds: Set<string>;
  /** Rows the server refused: localId → server's reason. */
  failedMessages: { localId: string; failure: string }[];
  /** Re-send a failed row. */
  onRetryMessage: (localId: string) => void;
  /** Abandon a failed row. Without this a permanent rejection - no send
   * permission, deleted channel - wedges the row on screen for good. */
  onDiscardMessage: (localId: string) => void;
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
  /** Newest-read point (ISO) for the unread divider; null hides it. */
  readWatermark?: string | null;
  /** Advance the read watermark - called with the newest message while the
   * list sits at the bottom, so unread stays unread while scrolled up. */
  onReadUpTo?: (message: Message) => void;
}

/** How many older pages a jump will pull in before giving up on finding it. */
const JUMP_MAX_PAGES = 12;

/** Rendered-row budget: scrollback beyond this is capped off until asked for,
 * so deep history can't grow the DOM without bound. */
const RENDER_CAP = 600;
const RENDER_CAP_STEP = 400;
const RENDER_CAP_MAX = 2000;

/**
 * A message the server wrote about the conversation, rather than a line someone
 * typed into it. Reading it as one of the author's remarks gets it wrong, so it
 * is drawn centred and unbubbled - keeping the name, since who changed what is
 * the whole point of saying it.
 *
 * The kind comes off `message.systemNotice`, which only the server sets. A kind
 * this build has never heard of falls back to the sentence the server wrote,
 * which already names the actor: a new notice on an old client is plain, not
 * missing.
 */
function SystemNotice({
  message,
  channel,
  selfId,
  profiles,
}: {
  message: Message;
  channel: { id: string; name: string | null; serverId: string | null };
  selfId: string | undefined;
  profiles?: Record<string, User>;
}) {
  const kind = systemNoticeKind(message);
  const call = callNotice(message);
  if (call) {
    return (
      <CallCard
        message={message}
        notice={call}
        channel={channel}
        selfId={selfId}
        profiles={profiles}
      />
    );
  }

  const name = message.author.id === selfId ? 'You' : message.author.displayName;
  const text = (kind && describeSystemNotice(kind, name)) ?? message.content;
  return (
    <div className="flex justify-center px-4 py-2">
      <p role="status" className="max-w-prose text-center text-xs leading-relaxed text-ink-muted">
        <span aria-hidden>- </span>
        {text}
        <span aria-hidden> -</span>
      </p>
    </div>
  );
}

/**
 * Scrollable message pane. Rendered oldest→newest so a screen reader reads the
 * log forward; the scroll position is managed manually instead (see the layout
 * effect): snap to the bottom while the user is at it, and shift by however
 * much older pages grew when they load, so the visible rows never move.
 */
export function MessageList({
  messages,
  channel,
  pendingMessageIds,
  failedMessages,
  onRetryMessage,
  onDiscardMessage,
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
  readWatermark,
  onReadUpTo,
}: MessageListProps) {
  const topSentinel = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const [flashId, setFlashId] = useState<string | null>(null);
  const [missingJump, setMissingJump] = useState(false);
  const flashTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  // "At the present" is a scroll-position question. With a top-down flex
  // column that means the bottom edge of the viewport, within a small slop.
  const [atBottom, setAtBottom] = useState(true);
  const atBottomRef = useRef(true);
  // Snapshotted once per mount (this component remounts with its keyed-by-
  // channel.id parent, so that's once per channel opened): the point new-
  // message comparisons hold steady against for the whole viewing session.
  // Reading the live watermark instead would self-erase the divider/count
  // the instant the list reaches the bottom, since the effect below advances
  // the persisted value on exactly that condition.
  const enteredWatermarkRef = useRef(readWatermark ?? null);
  // Older-history fetch in flight (top sentinel crossed): when it lands, the
  // rows must not jump, so the scroll offset is shifted by the added height.
  const olderLoadPending = useRef(false);
  // Scroll metrics from the previous layout pass, for that same shift.
  const prevHeight = useRef(0);
  // DOM budget for deep scrollback; a deep-link jump drops the cap for the
  // session, because a jump has to be able to reach the row it names.
  const [renderCap, setRenderCap] = useState(RENDER_CAP);
  const [hasJumped, setHasJumped] = useState(false);

  useEffect(() => {
    if (jumpToId) setHasJumped(true);
  }, [jumpToId]);

  const capped = !hasJumped && !jumpToId && messages.length > renderCap;
  // Everything the user can see is the newest messages; older ones sit behind
  // a "load earlier" edge until they are asked for.
  const rendered = useMemo(() => {
    if (!capped) return messages;
    return messages.slice(-renderCap);
  }, [messages, capped, renderCap]);

  const expandHistory = useCallback(() => {
    // The added rows land above the viewport, so preserve the scroll position
    // the same way an older-page fetch does.
    olderLoadPending.current = true;
    setRenderCap((c) => (c >= RENDER_CAP_MAX ? Infinity : c + RENDER_CAP_STEP));
  }, []);

  const onScroll = useCallback(() => {
    const el = scroller.current;
    if (!el) return;
    const bottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 24;
    if (bottom !== atBottomRef.current) {
      atBottomRef.current = bottom;
      setAtBottom(bottom);
    }
  }, []);

  const handleLoadOlder = useCallback(() => {
    olderLoadPending.current = true;
    onLoadOlder();
  }, [onLoadOlder]);

  // Manual scroll management, the price of chronological DOM order (a screen
  // reader used to walk the log newest-first): pin to the bottom while the
  // user is at it, and preserve the visible rows when older pages prepend.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const height = el.scrollHeight;
    const delta = height - prevHeight.current;
    prevHeight.current = height;
    if (delta === 0) {
      olderLoadPending.current = false;
    } else if (atBottomRef.current) {
      el.scrollTop = el.scrollHeight;
      olderLoadPending.current = false;
    } else if (delta > 0 && olderLoadPending.current) {
      el.scrollTop += delta;
      olderLoadPending.current = false;
    }
  }, [messages, rendered, hasOlder, isLoadingOlder]);

  // Images, video posters and link embeds settle their height after the layout
  // effect above has already run. The browser used to absorb that for free;
  // with `overflow-anchor: none` it no longer does, so the rows would slide out
  // from under a user sitting at the bottom. `load` does not bubble - capture.
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const onContentLoad = () => {
      if (atBottomRef.current) el.scrollTop = el.scrollHeight;
      prevHeight.current = el.scrollHeight;
    };
    el.addEventListener('load', onContentLoad, true);
    return () => el.removeEventListener('load', onContentLoad, true);
  }, []);

  // The composer growing to a second line, the connection banner mounting, a
  // reply bar appearing - none of these change scrollHeight, only the
  // scroller's own clientHeight, so the layout effect above never sees them
  // (its delta is scrollHeight - scrollHeight). Watch the scroller's own box
  // and re-pin the same way.
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const observer = new ResizeObserver(() => {
      if (atBottomRef.current) el.scrollTop = el.scrollHeight;
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const failedById = useMemo(
    () => new Map(failedMessages.map((f) => [f.localId, f.failure])),
    [failedMessages],
  );

  // While the list sits at the bottom the newest message is by definition read;
  // anything newer than the watermark arrived while the user was elsewhere.
  useEffect(() => {
    if (!atBottom || !onReadUpTo) return;
    const newest = messages[messages.length - 1];
    if (newest) onReadUpTo(newest);
  }, [messages, atBottom, onReadUpTo]);

  // First row the user hasn't read yet (chronological index), and how many
  // newer than the entry-time watermark there are in total. Both skip the
  // user's own messages: you have read what you just wrote, and a divider
  // sitting above your own line while the button counts zero reads as a bug.
  const isUnread = useCallback(
    (m: Message) => {
      const watermark = enteredWatermarkRef.current;
      return !!watermark && m.createdAt > watermark && m.author.id !== selfId;
    },
    [selfId],
  );
  const firstUnreadIndex = useMemo(
    () => messages.findIndex(isUnread),
    [messages, isUnread],
  );
  const newCount = useMemo(
    () => messages.filter(isUnread).length,
    [messages, isUnread],
  );
  // Same position, relative to the rendered window (outside it, no divider).
  const cutIndex = messages.length - rendered.length;
  const firstUnreadInView = firstUnreadIndex >= cutIndex ? firstUnreadIndex - cutIndex : -1;

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

  // Load older history when the (visual) top sentinel scrolls into view. The
  // cap state is a dependency: the sentinel only exists while uncapped, and
  // the observer has to (re)attach the moment it appears.
  useEffect(() => {
    const el = topSentinel.current;
    if (!el || !hasOlder) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) handleLoadOlder();
      },
      { root: scroller.current, rootMargin: '200px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasOlder, handleLoadOlder, capped]);

  const byId = useMemo(() => {
    const map = new Map<string, Message>();
    for (const m of messages) map.set(m.id, m);
    return map;
  }, [messages]);

  // Chronological walk decides grouping. Runs over the rendered window, so
  // the row before the first rendered message (the cap edge) does not exist
  // and the first row simply renders as a lead - the grouping stays honest.
  const rows = useMemo(
    () =>
      rendered.map((message, i) => {
        const prev = rendered[i - 1];
        const notice = isSystemNotice(message);
        const compact =
          !!prev &&
          // A notice is a break in the conversation, not a line of it: letting
          // the message after one group into the bubble above would hide its
          // header behind a divider.
          !isSystemNotice(prev) &&
          !notice &&
          prev.author.id === message.author.id &&
          !message.replyToId &&
          withinGroupWindow(prev.createdAt, message.createdAt);
        return { message, compact, notice };
      }),
    [rendered],
  );

  // Where the unread divider lands, or -1 for nowhere. Suppressed while the
  // list sits at the bottom, where a new message arriving would make it
  // flicker.
  const dividerIndex = atBottom ? -1 : firstUnreadInView;

  const plated = !!backgroundUrl;

  /**
   * The rows banded into the runs that share a plate: a lead message plus the
   * compact ones grouped under it. Anything that interrupts the conversation
   * ends a run early - a system notice, which is drawn unplated and centred,
   * and the unread divider, which has to sit between plates rather than
   * splitting one down the middle.
   *
   * Bands are built even with no background picture, where they render flat and
   * unwrapped, so the two paths agree on where a group begins and ends.
   */
  const bands = useMemo(() => {
    const out: { key: string; start: number; notice: boolean; rows: typeof rows }[] = [];
    rows.forEach((row, i) => {
      const open = out[out.length - 1];
      const breaks =
        !open || row.notice || open.notice || !row.compact || i === dividerIndex;
      if (breaks) {
        out.push({
          key: row.message.clientId ?? row.message.id,
          start: i,
          notice: row.notice,
          rows: [row],
        });
      } else {
        open.rows.push(row);
      }
    });
    return out;
  }, [rows, dividerIndex]);

  const renderRow = ({ message, compact }: (typeof rows)[number], groupEnd: boolean) => (
    <MessageItem
      key={message.clientId ?? message.id}
      message={message}
      pending={pendingMessageIds.has(message.id) && !failedById.has(message.id)}
      failed={failedById.has(message.id)}
      failure={failedById.get(message.id)}
      onRetry={() => onRetryMessage(message.id)}
      onDiscard={() => onDiscardMessage(message.id)}
      compact={compact}
      groupEnd={groupEnd}
      plated={plated}
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
  );

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      {/* Readability is the message groups' job now (see `.oc-plate`), so the
          picture keeps almost all of its strength here. What is left is a light
          tint to pull a blown-out photo back towards the surface colour, and a
          slight blur so fine detail stops fighting the unplated text - notices,
          the unread divider, the intro - that does sit straight on it. The blur
          bleeds transparent pixels in from the edges; the overscale hides it. */}
      {backgroundUrl && (
        <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
          <img
            src={backgroundUrl}
            alt=""
            className="h-full w-full scale-110 object-cover blur-[2px]"
          />
          <div className="absolute inset-0 bg-surface-2/30" />
        </div>
      )}
      {missingJump && (
        <p
          role="status"
          className="absolute inset-x-0 top-2 z-10 mx-auto w-fit rounded-lg border border-border bg-surface-4 px-3 py-1.5 text-xs text-ink-secondary shadow-lg"
        >
          {t("messageList.couldntFindThatMessageItMay")}
        </p>
      )}
      <div
        ref={scroller}
        onScroll={onScroll}
        className="flex flex-1 flex-col overflow-y-auto pb-2 [overflow-anchor:none]"
        role="log"
        aria-label={`Messages in ${channelName}`}
      >
        {/* `flex-col-reverse` used to pin a short conversation to the bottom
            for free; plain `flex-col` doesn't, so a spacer eats the surplus
            space above the content instead whenever the log is shorter than
            the viewport. */}
        <div aria-hidden className="mt-auto" />
        {/* Oldest first in the DOM, so a screen reader walks the log forward.
            The browser anchoring is off - the layout effect owns the scroll. */}
        {capped ? (
          <button
            type="button"
            onClick={expandHistory}
            className="mx-auto my-3 block rounded-md border border-border bg-surface-2 px-3 py-1.5 text-xs font-medium text-ink-secondary transition-colors hover:bg-surface-3 hover:text-ink"
          >
            {t("messageList.loadEarlierMessages")}
          </button>
        ) : hasOlder ? (
          <div ref={topSentinel} className="flex justify-center py-4">
            {isLoadingOlder && (
              <Loader2 aria-hidden className="size-5 animate-spin text-ink-muted" />
            )}
          </div>
        ) : (
          (intro ?? (
            <div className="px-4 pb-2 pt-6">
              <h2 className="text-xl font-bold">
                {t("messageList.welcomeToChannel", { channel: channelName })}
              </h2>
              <p className="text-sm text-ink-secondary">
                {t("messageList.thisIsTheStartOfThe")}
              </p>
            </div>
          ))
        )}
        {bands.map((band) => (
          <Fragment key={band.key}>
            {/* The divider belongs between the last read row and the first new
                one, which is always a band edge - `dividerIndex` forces one. */}
            {band.start === dividerIndex && (
              <div
                role="separator"
                aria-label={t("messageList.newMessagesStartHere")}
                className="flex items-center gap-3 px-4 py-2"
              >
                <span aria-hidden className="h-px flex-1 bg-border" />
                <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">
                  {t("messageList.newMessages")}
                </span>
                <span aria-hidden className="h-px flex-1 bg-border" />
              </div>
            )}
            {band.notice ? (
              <SystemNotice
                message={band.rows[0]!.message}
                channel={channel}
                selfId={selfId}
                profiles={mentionProfiles}
              />
            ) : plated ? (
              // The horizontal inset is the gutter the picture shows through,
              // and the plate owns the vertical rhythm a lead row's `mt-3`
              // carries when there is no background (see `MessageItem`).
              <div className="oc-plate mx-2 my-1.5 rounded-2xl py-1">
                {band.rows.map((row, i) => renderRow(row, i === band.rows.length - 1))}
              </div>
            ) : (
              band.rows.map((row, i) => renderRow(row, i === band.rows.length - 1))
            )}
          </Fragment>
        ))}
      </div>

      {/* Scrolled up? The only way back to the newest message used to be
          scrolling; a jump button with the waiting count fixes that. */}
      {!atBottom && (
        <button
          type="button"
          onClick={() => {
            const el = scroller.current;
            if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
          }}
          className="absolute bottom-3 left-1/2 z-10 flex -translate-x-1/2 items-center gap-1.5 rounded-full border border-border bg-surface-3 px-3 py-1.5 text-xs font-medium text-ink shadow-lg transition-colors hover:bg-surface-4"
        >
          <ArrowDown aria-hidden className="size-3.5" />
          {newCount > 0
            ? `${newCount > 99 ? '99+' : newCount} new message${newCount === 1 ? '' : 's'}`
            : 'Jump to present'}
        </button>
      )}
    </div>
  );
}
