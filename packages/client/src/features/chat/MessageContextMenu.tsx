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

const copyText = (text: string) => void navigator.clipboard?.writeText(text);

/** Read a message out loud. Cancels whatever was already speaking. */
function speak(text: string): void {
  const synth = window.speechSynthesis;
  if (!synth || !text.trim()) return;
  synth.cancel();
  synth.speak(new SpeechSynthesisUtterance(text));
}

export interface MessageContextMenuProps {
  message: Message;
  isOwn: boolean;
  /** Current user may delete/pin others' messages (MANAGE_MESSAGES). */
  canManage: boolean;
  onReply: () => void;
  onEdit: () => void;
  onForward: () => void;
  onTogglePin: () => void;
  onReport: () => void;
}

/**
 * Right-click menu for a message row. Everything here is also reachable from
 * the hover bar or a keyboard path; this is the fast way to it.
 */
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
  // The route we're on *is* the channel, DM or server alike, so the link is
  // just the current path plus the message the `?m=` jump understands.
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
          Add Reaction
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
          Edit Message
        </ContextMenuItem>
      )}

      <ContextMenuItem onSelect={onReply}>
        <Reply aria-hidden className="size-4" />
        Reply
      </ContextMenuItem>

      <ContextMenuItem onSelect={onForward}>
        <Forward aria-hidden className="size-4" />
        Forward
      </ContextMenuItem>

      {apps.length > 0 && (
        <ContextMenuSub>
          <ContextMenuSubTrigger>
            <Blocks aria-hidden className="size-4" />
            Apps
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
        Copy Text
      </ContextMenuItem>

      {canPin && (
        <ContextMenuItem onSelect={onTogglePin}>
          {message.pinned ? (
            <>
              <PinOff aria-hidden className="size-4" />
              Unpin Message
            </>
          ) : (
            <>
              <Pin aria-hidden className="size-4" />
              Pin Message
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
        Mark Unread
      </ContextMenuItem>

      <ContextMenuItem onSelect={() => copyText(messageLink)}>
        <LinkIcon aria-hidden className="size-4" />
        Copy Message Link
      </ContextMenuItem>

      <ContextMenuItem disabled={!message.content} onSelect={() => speak(message.content)}>
        <Volume2 aria-hidden className="size-4" />
        Speak Message
      </ContextMenuItem>

      {!isOwn && (
        <ContextMenuItem danger onSelect={onReport}>
          <Flag aria-hidden className="size-4" />
          Report Message
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
            Delete Message
          </ContextMenuItem>
        </>
      )}

      <ContextMenuSeparator />
      <ContextMenuItem onSelect={() => copyText(message.id)}>
        <Hash aria-hidden className="size-4" />
        Copy Message ID
      </ContextMenuItem>
    </ContextMenuContent>
  );
}
