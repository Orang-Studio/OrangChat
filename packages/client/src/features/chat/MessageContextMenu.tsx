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
  Reply,
  SmilePlus,
  Trash2,
  Volume2,
} from "lucide-react";
import type { Message } from "@orangchat/shared";
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
import { QUICK_EMOJIS } from "./emoji-data";
import { deleteMessage, toggleReaction } from "./socket-actions";
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
}: MessageContextMenuProps) {
  const { pathname } = useLocation();
  const messageLink = `${window.location.origin}${pathname}?m=${message.id}`;

  const react = (emoji: string) =>
    toggleReaction(
      { channelId: message.channelId, messageId: message.id, emoji },
      message.reactions.some((r) => r.emoji === emoji && r.me),
    );

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
      <div className="flex gap-0.5 pb-1">
        {QUICK_EMOJIS.slice(0, 4).map((emoji) => (
          <ContextMenuItem
            key={emoji}
            onSelect={() => react(emoji)}
            className="flex-1 justify-center py-1.5 text-lg"
          >
            {emoji}
          </ContextMenuItem>
        ))}
      </div>

      <ContextMenuSub>
        <ContextMenuSubTrigger>
          <SmilePlus aria-hidden className="size-4" />
          {t("messageContextMenu.addReaction")}
        </ContextMenuSubTrigger>
        <ContextMenuSubContent className="min-w-0">
          <div className="grid grid-cols-4 gap-0.5">
            {QUICK_EMOJIS.map((emoji) => (
              <ContextMenuItem
                key={emoji}
                onSelect={() => react(emoji)}
                className="justify-center py-1.5 text-lg"
              >
                {emoji}
              </ContextMenuItem>
            ))}
          </div>
        </ContextMenuSubContent>
      </ContextMenuSub>

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
