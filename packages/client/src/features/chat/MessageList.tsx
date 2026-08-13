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
import { dayKey, daysAgo, formatDayLabel, withinGroupWindow } from '../../lib/time';
import { CallCard } from '../voice/CallCard';
import { MessageItem } from './MessageItem';
import { t } from "../../lib/i18n";

interface MessageListProps {
  messages: Message[];

  channel: { id: string; name: string | null; serverId: string | null };
  pendingMessageIds: Set<string>;

  failedMessages: { localId: string; failure: string }[];

  onRetryMessage: (localId: string) => void;

  onDiscardMessage: (localId: string) => void;
  channelName: string;

  backgroundUrl?: string | null;
  hasOlder: boolean;
  isLoadingOlder: boolean;
  onLoadOlder: () => void;
  selfId: string | undefined;
  canManage: boolean;
  onReply: (message: Message) => void;

  mentionNames?: Record<string, string>;

  mentionUsers?: Record<string, { id: string; name: string }>;

  intro?: ReactNode;

  jumpToId?: string | null;

  replyToId?: string | null;

  mentionProfiles?: Record<string, User>;

  onJumpHandled?: () => void;

  readWatermark?: string | null;

  onReadUpTo?: (message: Message) => void;
}


const JUMP_MAX_PAGES = 12;

const JUMP_VIEWPORT_FRACTION = 0.28;


const RENDER_CAP = 600;
const RENDER_CAP_STEP = 400;
const RENDER_CAP_MAX = 2000;


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


function DaySeparator({ iso }: { iso: string }) {
  const days = daysAgo(iso);
  const label =
    days === 0
      ? t("messageList.today")
      : days === 1
        ? t("messageList.yesterday")
        : formatDayLabel(iso);
  return (
    <div role="separator" aria-label={label} className="flex items-center gap-3 px-4 py-2">
      <span aria-hidden className="h-px flex-1 bg-border" />
      <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">
        {label}
      </span>
      <span aria-hidden className="h-px flex-1 bg-border" />
    </div>
  );
}


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
  const [atBottom, setAtBottom] = useState(true);
  const atBottomRef = useRef(true);
  const enteredWatermarkRef = useRef(readWatermark ?? null);
  const olderLoadPending = useRef(false);
  const prevHeight = useRef(0);
  const [renderCap, setRenderCap] = useState(RENDER_CAP);
  const [hasJumped, setHasJumped] = useState(false);

  useEffect(() => {
    if (jumpToId) setHasJumped(true);
  }, [jumpToId]);

  const capped = !hasJumped && !jumpToId && messages.length > renderCap;
  const rendered = useMemo(() => {
    if (!capped) return messages;
    return messages.slice(-renderCap);
  }, [messages, capped, renderCap]);

  const expandHistory = useCallback(() => {
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

  useEffect(() => {
    if (!atBottom || !onReadUpTo) return;
    const newest = messages[messages.length - 1];
    if (newest) onReadUpTo(newest);
  }, [messages, atBottom, onReadUpTo]);

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
  const cutIndex = messages.length - rendered.length;
  const firstUnreadInView = firstUnreadIndex >= cutIndex ? firstUnreadIndex - cutIndex : -1;

  const latest = useRef({ hasOlder, isLoadingOlder, onLoadOlder });
  latest.current = { hasOlder, isLoadingOlder, onLoadOlder };

  useEffect(() => () => clearTimeout(flashTimer.current), []);


  const jumpTo = useCallback(async (messageId: string) => {
    setMissingJump(false);
    for (let page = 0; page <= JUMP_MAX_PAGES; page++) {
      const target = scroller.current?.querySelector<HTMLElement>(
        `[data-message-id="${CSS.escape(messageId)}"]`,
      );
      if (target) {
        const el = scroller.current!;
        el.scrollTo({
          top:
            el.scrollTop +
            target.getBoundingClientRect().top -
            el.getBoundingClientRect().top -
            el.clientHeight * JUMP_VIEWPORT_FRACTION,
          behavior: 'smooth',
        });
        setFlashId(messageId);
        clearTimeout(flashTimer.current);
        flashTimer.current = setTimeout(() => setFlashId(null), 2000);
        return;
      }
      if (!latest.current.hasOlder) break;
      if (!latest.current.isLoadingOlder) latest.current.onLoadOlder();
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    setMissingJump(true);
    setTimeout(() => setMissingJump(false), 4000);
  }, []);

  useEffect(() => {
    if (!jumpToId || messages.length === 0) return;
    void jumpTo(jumpToId);
    onJumpHandled?.();
  }, [jumpToId, messages.length > 0]);

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

  const rows = useMemo(
    () =>
      rendered.map((message, i) => {
        const prev = rendered[i - 1];
        const notice = isSystemNotice(message);
        const newDay = !prev || dayKey(prev.createdAt) !== dayKey(message.createdAt);
        const compact =
          !!prev &&
          !newDay &&
          !isSystemNotice(prev) &&
          !notice &&
          prev.author.id === message.author.id &&
          !message.replyToId &&
          withinGroupWindow(prev.createdAt, message.createdAt);
        return { message, compact, notice, newDay };
      }),
    [rendered],
  );

  const dividerIndex = atBottom ? -1 : firstUnreadInView;

  const plated = !!backgroundUrl;


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
            {band.rows[0]!.newDay && <DaySeparator iso={band.rows[0]!.message.createdAt} />}
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
