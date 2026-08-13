import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { Pencil, Pin, Reply, Trash2, TriangleAlert } from "lucide-react";
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
import { ReactionPicker, ReactionStrip } from "./Reactions";
import { deleteMessage, editMessage } from "./socket-actions";
import { MessageContextMenu } from "./MessageContextMenu";
import { ForwardDialog } from "./ForwardDialog";
import { ReportMessageDialog } from "./ReportMessageDialog";
import { BotTag } from "../../components/BotTag";
import { t } from "../../lib/i18n";

export interface MessageItemProps {
  message: Message;

  pending?: boolean;

  failed?: boolean;

  failure?: string;

  onRetry?: () => void;

  onDiscard?: () => void;

  compact: boolean;

  groupEnd?: boolean;

  plated?: boolean;

  replyTo?: Message;
  isOwn: boolean;

  canManage: boolean;
  onReply: (message: Message) => void;

  onJumpTo?: (messageId: string) => void;

  flash?: boolean;

  replying?: boolean;

  mentionNames?: Record<string, string>;

  mentionUsers?: Record<string, { id: string; name: string }>;

  selfId?: string;

  mentionProfiles?: Record<string, User>;
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
          {t("messageItem.enterToSaveEscToCancel")}
          {error && <span className="ml-2 text-danger">{error}</span>}
        </span>
        <span className="flex shrink-0 gap-1">
          <button
            type="button"
            disabled={saving}
            onClick={onDone}
            className="rounded-md px-2 py-1 text-ink-secondary transition-colors hover:bg-surface-3 hover:text-ink disabled:opacity-50"
          >
            {t("common.cancel")}
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
  failed = false,
  failure,
  onRetry,
  onDiscard,
  compact,
  groupEnd = true,
  plated = false,
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
  const pinged = !isOwn && !pending && mentionsViewer(message.content, selfId, selfUsername);

  const onTap = (e: React.MouseEvent) => {
    if (!window.matchMedia("(pointer: coarse)").matches) return;
    if ((e.target as HTMLElement).closest("button, a, textarea")) return;
    setTouchActions((v) => !v);
  };

  return (
    <>
      <ContextMenu>
        <ContextMenuTrigger asChild disabled={pending || failed}>
          <div
            data-message-id={message.id}
            onClick={pending || failed ? undefined : onTap}
            aria-label={
              pending ? "Sending message" : failed ? "Message failed to send" : undefined
            }
            className={cn(
              "oc-message group relative px-4 hover:bg-surface-3/40",
              compact ? "pt-0" : "pt-0.5",
              groupEnd ? "pb-0.5" : "pb-0",
              !compact && !plated && "oc-message-lead mt-5",
              touchActions && "bg-surface-3/40",
              pending && "pointer-events-none opacity-50",
              failed && "bg-danger/5",
              pinged && "oc-message-pinged border-l-2 border-primary bg-primary/[0.06] hover:bg-primary/10",
              replying && "oc-message-replying bg-primary/[0.08] hover:bg-primary/10",
              flash && "bg-primary/15 hover:bg-primary/15",
            )}
          >
            {message.pinned && (
              <p className="mb-0.5 flex items-center gap-1.5 pl-12 text-xs text-ink-muted">
                <Pin aria-hidden className="size-3.5" />
                {t("messageItem.pinned")}
              </p>
            )}

            {/* Reply reference line - clicking it walks back to what was replied to. */}
            {message.replyToId && (
              <button
                type="button"
                onClick={() => onJumpTo?.(message.replyToId!)}
                title={t("messageItem.jumpToTheRepliedMessage")}
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
                  <span className="italic">{t("messageItem.originalMessage")}</span>
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
                  <div
                    className={cn(
                      "break-words text-sm leading-relaxed",
                      failed && "text-danger",
                    )}
                  >
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
                        {t("messageItem.edited")}
                      </span>
                    )}
                  </div>
                )}

                {!editing && (
                  <MessageAttachments
                    attachments={message.attachments}
                    context={{
                      message,
                      mentions: mentionNames,
                      mentionUsers,
                      selfId,
                    }}
                  />
                )}

                {!editing && <LinkEmbeds content={message.content} />}

                {/* The server refused this row. Keep the words, say why, and
                    offer both ways out - silently dropping it was losing user
                    text, but a rejection the server will never accept needs a
                    way off the screen too. */}
                {failed && (
                  <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
                    <span className="flex min-w-0 items-center gap-1.5 font-medium text-danger">
                      <TriangleAlert aria-hidden className="size-3.5 shrink-0" />
                      <span className="truncate">{failure ?? "Failed to send"}</span>
                    </span>
                    <button
                      type="button"
                      onClick={onRetry}
                      className="font-semibold text-primary transition-colors hover:text-primary-hover"
                    >
                      {t("common.retry")}
                    </button>
                    <button
                      type="button"
                      onClick={onDiscard}
                      className="font-semibold text-danger transition-colors hover:text-danger/80"
                    >
                      {t("common.delete")}
                    </button>
                  </div>
                )}

                <ReactionStrip message={message} className="mt-1" />
              </div>
            </div>

            {/* Hover actions (tap-toggled on touch) */}
            {!editing && !pending && !failed && (
              <div
                className={cn(
                  "absolute -top-3 right-4 hidden items-center gap-0.5 rounded-lg border border-border bg-surface-2 p-0.5 shadow group-hover:flex",
                  touchActions && "flex",
                )}
              >
                <ReactionPicker message={message} />
                <button
                  type="button"
                  aria-label={t("messageItem.reply")}
                  onClick={() => onReply(message)}
                  className="rounded p-1.5 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink md:p-1"
                >
                  <Reply aria-hidden className="size-4" />
                </button>
                {isOwn && (
                  <button
                    type="button"
                    aria-label={t("messageItem.editMessage")}
                    onClick={() => setEditing(true)}
                    className="rounded p-1.5 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink md:p-1"
                  >
                    <Pencil aria-hidden className="size-4" />
                  </button>
                )}
                {(isOwn || canManage) && (
                  <button
                    type="button"
                    aria-label={t("messageItem.deleteMessage")}
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
        {!pending && !failed && (
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
