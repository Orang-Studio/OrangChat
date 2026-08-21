import { useLocation } from "react-router-dom";
import {
  Blocks,
  Copy,
  Forward,
  Flag,
  Hash,
  Link as LinkIcon,
  MailQuestionMark,
  Pencil,
  Pin,
  PinOff,
  Plus,
  Reply,
  Trash2,
  Volume2,
} from "lucide-react";
import type { Message } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import {
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
} from "../../components/ui/ContextMenu";
import { usePluginMessageActions } from "../plugins/store";
import type { PluginMessage } from "../plugins/types";
import { unreadActions } from "../../stores/unread";
import { markChannelUnread } from "../unread/api";
import { useEmojiMap, withMessageEmojis } from "../emojis/queries";
import {
  ReactionEmoji,
  reactionValue,
  reactWith,
  useReactionQuickPicks,
} from "./Reactions";
import { deleteMessage } from "./socket-actions";
import { t } from "../../lib/i18n";

const copyText = (text: string) => void navigator.clipboard?.writeText(text);


function speak(text: string): void {
  const synth = window.speechSynthesis;
  if (!synth || !text.trim()) return;
  synth.cancel();
  synth.speak(new SpeechSynthesisUtterance(text));
}

export interface MessageContextMenuProps {
  message: Message;
  isOwn: boolean;

  canManage: boolean;
  onReply: () => void;
  onEdit: () => void;
  onForward: () => void;
  onTogglePin: () => void;
  onReport: () => void;

  onOpenReactionPicker: () => void;
}


export function MessageContextMenu({
  message,
  isOwn,
  canManage,
  onReply,
  onEdit,
  onForward,
  onTogglePin,
  onReport,
  onOpenReactionPicker,
}: MessageContextMenuProps) {
  const { pathname } = useLocation();
  const messageLink = `${window.location.origin}${pathname}?m=${message.id}`;

  const quickPicks = useReactionQuickPicks();
  const usableEmojis = useEmojiMap();
  const emojis = withMessageEmojis(usableEmojis, message.emojis);

  const canPin = isOwn || canManage;

  const pluginActions = usePluginMessageActions();
  const pluginMessage: PluginMessage = {
    id: message.id,
    channelId: message.channelId,
    content: message.content,
    authorId: message.author.id,
    authorName: message.author.displayName,
    createdAt: message.createdAt,
    own: isOwn,
  };
  const apps = pluginActions.filter(({ action }) => action.visible?.(pluginMessage) ?? true);

  return (
    <ContextMenuContent className="w-60">
      {/* Tier one: what this viewer actually reacts with, one click away. The
          `+` hands off to the full picker rather than nesting it in a submenu. */}
      <div className="flex items-center gap-0.5 pb-1">
        {quickPicks.map((pick) => {
          const value = reactionValue(pick);
          const mine = message.reactions.some((r) => r.emoji === value && r.me);
          return (
            <ContextMenuItem
              key={value}
              title={pick.custom ? `:${pick.custom.name}:` : pick.insert}
              onSelect={() => reactWith(message, pick)}
              className={cn(
                "flex-1 justify-center py-1.5 text-lg",
                mine && "bg-primary-soft",
              )}
            >
              <ReactionEmoji emoji={value} emojis={emojis} />
            </ContextMenuItem>
          );
        })}
        <ContextMenuItem
          aria-label={t("messageContextMenu.addReaction")}
          title={t("messageContextMenu.addReaction")}
          onSelect={onOpenReactionPicker}
          className="justify-center px-2 py-1.5 text-ink-muted"
        >
          <Plus aria-hidden className="size-5" />
        </ContextMenuItem>
      </div>

      {isOwn && (
        <ContextMenuItem onSelect={onEdit}>
          <Pencil aria-hidden className="size-4" />
          {t("messageContextMenu.editMessage")}
        </ContextMenuItem>
      )}

      <ContextMenuItem onSelect={onReply}>
        <Reply aria-hidden className="size-4" />
        {t("messageContextMenu.reply")}
      </ContextMenuItem>

      <ContextMenuItem onSelect={onForward}>
        <Forward aria-hidden className="size-4" />
        {t("messageContextMenu.forward")}
      </ContextMenuItem>

      {apps.length > 0 && (
        <ContextMenuSub>
          <ContextMenuSubTrigger>
            <Blocks aria-hidden className="size-4" />
            {t("messageContextMenu.apps")}
          </ContextMenuSubTrigger>
          <ContextMenuSubContent>
            {apps.map(({ pluginId, pluginName, action, ctx }) => (
              <ContextMenuItem
                key={`${pluginId}:${action.id}`}
                title={pluginName}
                onSelect={() => {
                  try {
                    action.run(pluginMessage, ctx);
                  } catch (error) {
                    console.error(`[plugins] ${pluginId} action failed`, error);
                  }
                }}
              >
                {action.label}
              </ContextMenuItem>
            ))}
          </ContextMenuSubContent>
        </ContextMenuSub>
      )}

      <ContextMenuSeparator />

      <ContextMenuItem disabled={!message.content} onSelect={() => copyText(message.content)}>
        <Copy aria-hidden className="size-4" />
        {t("messageContextMenu.copyText")}
      </ContextMenuItem>

      {canPin && (
        <ContextMenuItem onSelect={onTogglePin}>
          {message.pinned ? (
            <>
              <PinOff aria-hidden className="size-4" />
              {t("messageContextMenu.unpinMessage")}
            </>
          ) : (
            <>
              <Pin aria-hidden className="size-4" />
              {t("messageContextMenu.pinMessage")}
            </>
          )}
        </ContextMenuItem>
      )}

      <ContextMenuItem
        onSelect={() =>
          void markChannelUnread(message.channelId, message.id)
            .then(unreadActions.set)
            .catch(() => {})
        }
      >
        <MailQuestionMark aria-hidden className="size-4" />
        {t("messageContextMenu.markUnread")}
      </ContextMenuItem>

      <ContextMenuItem onSelect={() => copyText(messageLink)}>
        <LinkIcon aria-hidden className="size-4" />
        {t("messageContextMenu.copyMessageLink")}
      </ContextMenuItem>

      <ContextMenuItem disabled={!message.content} onSelect={() => speak(message.content)}>
        <Volume2 aria-hidden className="size-4" />
        {t("messageContextMenu.speakMessage")}
      </ContextMenuItem>

      {!isOwn && (
        <ContextMenuItem danger onSelect={onReport}>
          <Flag aria-hidden className="size-4" />
          {t("messageContextMenu.reportMessage")}
        </ContextMenuItem>
      )}

      {(isOwn || canManage) && (
        <>
          <ContextMenuSeparator />
          <ContextMenuItem
            danger
            onSelect={() =>
              void deleteMessage({
                channelId: message.channelId,
                messageId: message.id,
              })
            }
          >
            <Trash2 aria-hidden className="size-4" />
            {t("messageContextMenu.deleteMessage")}
          </ContextMenuItem>
        </>
      )}

      <ContextMenuSeparator />
      <ContextMenuItem onSelect={() => copyText(message.id)}>
        <Hash aria-hidden className="size-4" />
        {t("messageContextMenu.copyMessageId")}
      </ContextMenuItem>
    </ContextMenuContent>
  );
}
