import { useState } from "react";
import * as Popover from "@radix-ui/react-popover";
import { Pencil, Reply, SmilePlus, Trash2 } from "lucide-react";
import type { Message } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { formatFullTime, formatMessageTime } from "../../lib/time";
import { Avatar } from "../../components/Avatar";
import { RichText } from "../../lib/markdown";
import { useEmojiMap } from "../emojis/queries";
import { ProfileDialog } from "../profile/ProfileDialog";
import { isDirectMediaMessage, LinkEmbeds } from "./LinkEmbeds";
import { MessageAttachments } from "./MessageAttachments";
import { deleteMessage, editMessage, toggleReaction } from "./socket-actions";
import { QUICK_EMOJIS } from "./emoji-data";

export interface MessageItemProps {
  message: Message;
  /** Render compact (no avatar/header) - same author, close in time. */
  compact: boolean;
  /** The message this one replies to, when loaded. */
  replyTo?: Message;
  isOwn: boolean;
  /** Current user may delete others' messages (MANAGE_MESSAGES). */
  canManage: boolean;
  onReply: (message: Message) => void;
  /** userId → display name, for resolving `<@id>` mentions in content. */
  mentionNames?: Record<string, string>;
  /** The viewer's id, so mentions of them highlight. */
  selfId?: string;
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

  const save = async () => {
    const content = draft.trim();
    if (!content || content === message.content) return onDone();
    try {
      await editMessage({ channelId: message.channelId, messageId: message.id, content });
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Edit failed");
    }
  };

  return (
    <div className="mt-0.5">
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            void save();
          }
          if (e.key === "Escape") onDone();
        }}
        rows={Math.min(6, draft.split("\n").length)}
        autoFocus
        className="w-full resize-none rounded-lg border border-border bg-surface-1 px-3 py-2 text-base md:text-sm"
      />
      <p className="mt-0.5 text-xs text-ink-muted">
        Enter to save · Esc to cancel
        {error && <span className="ml-2 text-danger">{error}</span>}
      </p>
    </div>
  );
}

export function MessageItem({
  message,
  compact,
  replyTo,
  isOwn,
  canManage,
  onReply,
  mentionNames,
  selfId,
}: MessageItemProps) {
  const [editing, setEditing] = useState(false);
  const [touchActions, setTouchActions] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const emojis = useEmojiMap();

  // Touch screens have no hover - tapping the message toggles the action bar.
  const onTap = (e: React.MouseEvent) => {
    if (!window.matchMedia("(pointer: coarse)").matches) return;
    if ((e.target as HTMLElement).closest("button, a, textarea")) return;
    setTouchActions((v) => !v);
  };

  return (
    <div
      onClick={onTap}
      className={cn(
        "oc-message group relative px-4 py-0.5 hover:bg-surface-3/40",
        !compact && "oc-message-lead mt-3",
        touchActions && "bg-surface-3/40",
      )}
    >
      {/* Reply reference line */}
      {message.replyToId && (
        <div className="mb-0.5 flex items-center gap-1.5 pl-12 text-xs text-ink-muted">
          <Reply aria-hidden className="size-3.5 -scale-x-100" />
          {replyTo ? (
            <>
              <span className="font-medium text-ink-secondary">
                {replyTo.author.displayName}
              </span>
              <span className="truncate">{replyTo.content}</span>
            </>
          ) : (
            <span className="italic">Original message</span>
          )}
        </div>
      )}

      <div className="flex gap-3">
        {compact ? (
          <span className="w-9 shrink-0 pt-1 text-right text-[10px] leading-5 text-ink-muted opacity-0 group-hover:opacity-100">
            {formatMessageTime(message.createdAt).slice(-5)}
          </span>
        ) : (
          <button
            type="button"
            onClick={() => setProfileOpen(true)}
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
                onClick={() => setProfileOpen(true)}
                className="font-semibold hover:underline"
              >
                {message.author.displayName}
              </button>
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
                  selfId={selfId}
                  emojis={emojis}
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
                  <span className="text-xs font-semibold text-ink-secondary">
                    {r.count}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Hover actions (tap-toggled on touch) */}
      {!editing && (
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

      {profileOpen && (
        <ProfileDialog
          user={message.author}
          open={profileOpen}
          onOpenChange={setProfileOpen}
        />
      )}
    </div>
  );
}
