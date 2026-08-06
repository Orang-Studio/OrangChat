import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import * as Popover from "@radix-ui/react-popover";
import { Pencil, Pin, Reply, SmilePlus, Trash2 } from "lucide-react";
import type { Message, User } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { ContextMenu, ContextMenuTrigger } from "../../components/ui/ContextMenu";
import { setMessagePinned } from "../messages/api";
import { formatFullTime, formatMessageTime } from "../../lib/time";
import { Avatar } from "../../components/Avatar";
import { RichText, mentionsViewer } from "../../lib/markdown";
import { useAuthStore } from "../../stores/auth";
import { useEmojiMap, withMessageEmojis } from "../emojis/queries";
import { ProfileDialog } from "../profile/ProfileDialog";
import { isDirectMediaMessage, LinkEmbeds } from "./LinkEmbeds";
import { MessageAttachments } from "./MessageAttachments";
import { deleteMessage, editMessage, toggleReaction } from "./socket-actions";
import { QUICK_EMOJIS } from "./emoji-data";
import { MessageContextMenu } from "./MessageContextMenu";
import { ForwardDialog } from "./ForwardDialog";
import { ReportMessageDialog } from "./ReportMessageDialog";
import { BotTag } from "../../components/BotTag";

export interface MessageItemProps {
  message: Message;
  /** Optimistic local row waiting for server confirmation. */
  pending?: boolean;
  /** Render compact (no avatar/header) - same author, close in time. */
  compact: boolean;
  /** The message this one replies to, when loaded. */
  replyTo?: Message;
  isOwn: boolean;
  /** Current user may delete others' messages (MANAGE_MESSAGES). */
  canManage: boolean;
  onReply: (message: Message) => void;
  /** Scroll the list to another message (the one this replies to). */
  onJumpTo?: (messageId: string) => void;
  /** Briefly highlighted because something just jumped to it. */
  flash?: boolean;
  /** The message currently selected as the reply target. */
  replying?: boolean;
  /** userId → display name, for resolving `<@id>` mentions in content. */
  mentionNames?: Record<string, string>;
  /** username → user, for resolving `@username` mentions in content. */
  mentionUsers?: Record<string, { id: string; name: string }>;
  /** The viewer's id, so mentions of them highlight. */
  selfId?: string;
  /** Full users for resolved mentions, so a mention can open a profile. */
  mentionProfiles?: Record<string, User>;
}

function ReactionPicker({ message }: { message: Message }) {
  const [open, setOpen] = useState(false);
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger
        aria-label="Add reaction"
        className="rounded p-1.5 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink md:p-1"
      >
        <SmilePlus aria-hidden className="size-4" />
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          side="top"
          sideOffset={4}
          className="z-50 flex gap-0.5 rounded-xl border border-border bg-surface-4 p-1.5 shadow-xl"
        >
          {QUICK_EMOJIS.map((emoji) => {
            const mine = message.reactions.some((r) => r.emoji === emoji && r.me);
            return (
              <button
                key={emoji}
                type="button"
                onClick={() => {
                  toggleReaction(
                    { channelId: message.channelId, messageId: message.id, emoji },
                    mine,
                  );
                  setOpen(false);
                }}
                className={cn(
                  "rounded-lg p-1.5 text-lg leading-none transition-colors hover:bg-surface-2",
                  mine && "bg-primary-soft",
                )}
              >
                {emoji}
              </button>
            );
          })}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

function EditForm({ message, onDone }: { message: Message; onDone: () => void }) {
  const [draft, setDraft] = useState(message.content);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const editor = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    setDraft(message.content);
  }, [message.content]);

  useLayoutEffect(() => {
    const field = editor.current;
    if (!field) return;
    field.focus();
    field.setSelectionRange(field.value.length, field.value.length);
  }, []);

  // An attachment carries the message on its own, so clearing the text is a
  // real edit there. Without something else on the row it would only leave a
  // blank message, so an empty draft still means "never mind".
  const mayBeEmpty = message.attachments.length > 0;

  const save = async () => {
    if (saving) return;
    const content = draft.trim();
    if (content === message.content || (!content && !mayBeEmpty)) return onDone();
    setSaving(true);
    setError(null);
    try {
      await editMessage(message, content);
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Edit failed");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="mt-0.5">
      <textarea
        ref={editor}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        disabled={saving}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            void save();
          }
          if (e.key === "Escape" && !saving) onDone();
        }}
        rows={Math.min(6, draft.split("\n").length)}
        autoFocus
        className="w-full resize-none rounded-lg border border-border bg-surface-1 px-3 py-2 text-base md:text-sm"
      />
      <div className="mt-1 flex items-center justify-between gap-2 text-xs text-ink-muted">
        <span>
          Enter to save · Esc to cancel
          {error && <span className="ml-2 text-danger">{error}</span>}
        </span>
        <span className="flex shrink-0 gap-1">
          <button
            type="button"
            disabled={saving}
            onClick={onDone}
            className="rounded-md px-2 py-1 text-ink-secondary transition-colors hover:bg-surface-3 hover:text-ink disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={saving}
            onClick={() => void save()}
            className="rounded-md bg-primary px-2 py-1 font-medium text-ink-on-primary transition-colors hover:bg-primary-hover disabled:opacity-50"
          >
            {saving ? "Saving..." : "Save"}
          </button>
        </span>
      </div>
    </div>
  );
}

export function MessageItem({
  message,
  pending = false,
  compact,
  replyTo,
  isOwn,
  canManage,
  onReply,
  onJumpTo,
  flash = false,
  replying = false,
  mentionNames,
  mentionUsers,
  selfId,
  mentionProfiles,
}: MessageItemProps) {
  const [editing, setEditing] = useState(false);
  const [touchActions, setTouchActions] = useState(false);
  const [profileUser, setProfileUser] = useState<User | null>(null);
  const [forwardOpen, setForwardOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const usableEmojis = useEmojiMap();
  const emojis = useMemo(
    () => withMessageEmojis(usableEmojis, message.emojis),
    [usableEmojis, message.emojis],
  );
  const selfUsername = useAuthStore((s) => s.user?.username);
  // highlight the whole row when the message pings us (but never our own).
  const pinged = !isOwn && !pending && mentionsViewer(message.content, selfId, selfUsername);

  // Touch screens have no hover - tapping the message toggles the action bar.
  const onTap = (e: React.MouseEvent) => {
    if (!window.matchMedia("(pointer: coarse)").matches) return;
    if ((e.target as HTMLElement).closest("button, a, textarea")) return;
    setTouchActions((v) => !v);
  };

  return (
    <>
      <ContextMenu>
        <ContextMenuTrigger asChild disabled={pending}>
          <div
            data-message-id={message.id}
            onClick={pending ? undefined : onTap}
            aria-label={pending ? "Sending message" : undefined}
            className={cn(
              "oc-message group relative px-4 py-0.5 hover:bg-surface-3/40",
              !compact && "oc-message-lead mt-3",
              touchActions && "bg-surface-3/40",
              pending && "pointer-events-none opacity-50",
              pinged && "oc-message-pinged border-l-2 border-primary bg-primary/[0.06] hover:bg-primary/10",
              replying && "oc-message-replying bg-primary/[0.08] hover:bg-primary/10",
              // Static rather than animated so it still lands under reduced motion.
              flash && "bg-primary/15 hover:bg-primary/15",
            )}
          >
            {message.pinned && (
              <p className="mb-0.5 flex items-center gap-1.5 pl-12 text-xs text-ink-muted">
                <Pin aria-hidden className="size-3.5" />
                Pinned
              </p>
            )}

            {/* Reply reference line - clicking it walks back to what was replied to. */}
            {message.replyToId && (
              <button
                type="button"
                onClick={() => onJumpTo?.(message.replyToId!)}
                title="Jump to the replied message"
                className="mb-0.5 flex w-full items-center gap-1.5 pl-12 pr-2 text-left text-xs text-ink-muted transition-colors hover:text-ink"
              >
                <Reply aria-hidden className="size-3.5 shrink-0 -scale-x-100" />
                {replyTo ? (
                  <>
                    <span className="shrink-0 font-medium text-ink-secondary">
                      {replyTo.author.displayName}
                    </span>
                    <span className="truncate">{replyTo.content}</span>
                  </>
                ) : (
                  <span className="italic">Original message</span>
                )}
              </button>
            )}

            <div className="flex gap-3">
              {compact ? (
                <span className="w-9 shrink-0 pt-1 text-right text-[10px] leading-5 text-ink-muted opacity-0 group-hover:opacity-100">
                  {formatMessageTime(message.createdAt).slice(-5)}
                </span>
              ) : (
                <button
                  type="button"
                  onClick={() => setProfileUser(message.author)}
                  aria-label={`View ${message.author.displayName}'s profile`}
                  className="mt-0.5 shrink-0"
                >
                  <Avatar user={message.author} />
                </button>
              )}

              <div className="min-w-0 flex-1">
                {!compact && (
                  <p className="flex items-baseline gap-2">
                    <button
                      type="button"
                      onClick={() => setProfileUser(message.author)}
                      className="font-semibold hover:underline"
                    >
                      {message.author.displayName}
                    </button>
                    {message.author.bot ? <BotTag /> : null}
                    <time
                      dateTime={message.createdAt}
                      title={formatFullTime(message.createdAt)}
                      className="text-xs text-ink-muted"
                    >
                      {formatMessageTime(message.createdAt)}
                    </time>
                  </p>
                )}

                {editing ? (
                  <EditForm message={message} onDone={() => setEditing(false)} />
                ) : (
                  <div className="break-words text-sm leading-relaxed">
                    {!isDirectMediaMessage(message.content) && (
                      <RichText
                        content={message.content}
                        mentions={mentionNames}
                         mentionUsers={mentionUsers}
                         selfId={selfId}
                         emojis={emojis}
                         onMentionClick={(userId) => {
                           const user = mentionProfiles?.[userId];
                           if (user) setProfileUser(user);
                         }}
                      />
                    )}
                    {message.editedAt && (
                      <span
                        title={formatFullTime(message.editedAt)}
                        className="ml-1.5 text-[10px] text-ink-muted"
                      >
                        (edited)
                      </span>
                    )}
                  </div>
                )}

                {!editing && <MessageAttachments attachments={message.attachments} />}

                {!editing && <LinkEmbeds content={message.content} />}

                {/* Reactions */}
                {message.reactions.length > 0 && (
                  <div className="mt-1 flex flex-wrap gap-1">
                    {message.reactions.map((r) => (
                      <button
                        key={r.emoji}
                        type="button"
                        aria-pressed={r.me}
                        onClick={() =>
                          toggleReaction(
                            {
                              channelId: message.channelId,
                              messageId: message.id,
                              emoji: r.emoji,
                            },
                            r.me,
                          )
                        }
                        className={cn(
                          "flex items-center gap-1 rounded-md border px-2 py-0.5 text-sm transition-colors",
                          r.me
                            ? "border-primary bg-primary-soft"
                            : "border-border bg-surface-2 hover:border-border-strong",
                        )}
                      >
                        <span>{r.emoji}</span>
                        <span className="text-xs font-semibold text-ink-secondary">{r.count}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Hover actions (tap-toggled on touch) */}
            {!editing && !pending && (
              <div
                className={cn(
                  "absolute -top-3 right-4 hidden items-center gap-0.5 rounded-lg border border-border bg-surface-2 p-0.5 shadow group-hover:flex",
                  touchActions && "flex",
                )}
              >
                <ReactionPicker message={message} />
                <button
                  type="button"
                  aria-label="Reply"
                  onClick={() => onReply(message)}
                  className="rounded p-1.5 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink md:p-1"
                >
                  <Reply aria-hidden className="size-4" />
                </button>
                {isOwn && (
                  <button
                    type="button"
                    aria-label="Edit message"
                    onClick={() => setEditing(true)}
                    className="rounded p-1.5 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink md:p-1"
                  >
                    <Pencil aria-hidden className="size-4" />
                  </button>
                )}
                {(isOwn || canManage) && (
                  <button
                    type="button"
                    aria-label="Delete message"
                    onClick={() =>
                      void deleteMessage({
                        channelId: message.channelId,
                        messageId: message.id,
                      })
                    }
                    className="rounded p-1.5 text-ink-muted transition-colors hover:bg-surface-3 hover:text-danger md:p-1"
                  >
                    <Trash2 aria-hidden className="size-4" />
                  </button>
                )}
              </div>
            )}

            {profileUser && (
              <ProfileDialog
                user={profileUser}
                open
                onOpenChange={(open) => {
                  if (!open) setProfileUser(null);
                }}
              />
            )}
          </div>
        </ContextMenuTrigger>
        {!pending && (
          <MessageContextMenu
            message={message}
            isOwn={isOwn}
            canManage={canManage}
            onReply={() => onReply(message)}
            onEdit={() => setEditing(true)}
            onForward={() => setForwardOpen(true)}
            onReport={() => setReportOpen(true)}
            onTogglePin={() =>
              void setMessagePinned(message.channelId, message.id, !message.pinned).catch(() => {})
            }
          />
        )}
      </ContextMenu>

      {forwardOpen && (
        <ForwardDialog message={message} open={forwardOpen} onOpenChange={setForwardOpen} />
      )}
      {reportOpen && (
        <ReportMessageDialog message={message} open={reportOpen} onOpenChange={setReportOpen} />
      )}
    </>
  );
}
