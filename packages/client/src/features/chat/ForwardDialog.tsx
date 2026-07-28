import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Hash, Loader2, Send, Users } from "lucide-react";
import type { Message } from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { cn } from "../../lib/cn";
import { useAuthStore } from "../../stores/auth";
import { conversationName, otherParticipants, useConversations } from "../dms/queries";
import { useServerDetail } from "../servers/queries";
import { sendMessage } from "./socket-actions";

interface ForwardDialogProps {
  message: Message;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Send an existing message's text somewhere else. Targets are the places the
 * client already knows about without extra fetches: every DM/group conversation,
 * plus the text channels of the server currently open.
 */
export function ForwardDialog({ message, open, onOpenChange }: ForwardDialogProps) {
  const navigate = useNavigate();
  const { serverId } = useParams();
  const selfId = useAuthStore((s) => s.user?.id);
  const { data: conversations } = useConversations();
  const { data: serverDetail } = useServerDetail(open ? serverId : undefined);
  const [query, setQuery] = useState("");
  const [sendingTo, setSendingTo] = useState<string | null>(null);

  const normalized = query.trim().toLowerCase();

  const channels = useMemo(
    () =>
      (serverDetail?.channels ?? [])
        .filter((c) => c.type === "text" && c.id !== message.channelId)
        .sort((a, b) => a.position - b.position)
        .filter((c) => !normalized || (c.name ?? "").toLowerCase().includes(normalized)),
    [serverDetail, message.channelId, normalized],
  );

  const dms = useMemo(
    () =>
      (conversations ?? [])
        .filter((c) => c.id !== message.channelId)
        .filter(
          (c) => !normalized || conversationName(c, selfId).toLowerCase().includes(normalized),
        ),
    [conversations, message.channelId, normalized, selfId],
  );

  const forward = async (channelId: string, to: string) => {
    setSendingTo(channelId);
    try {
      await sendMessage({ channelId, content: message.content });
      onOpenChange(false);
      navigate(to);
    } finally {
      setSendingTo(null);
    }
  };

  const Row = ({
    channelId,
    to,
    icon,
    label,
  }: {
    channelId: string;
    to: string;
    icon: React.ReactNode;
    label: string;
  }) => (
    <li>
      <button
        type="button"
        disabled={sendingTo !== null}
        onClick={() => void forward(channelId, to)}
        className={cn(
          "flex w-full items-center gap-2.5 rounded-lg px-2 py-2 text-left text-sm transition-colors",
          "hover:bg-surface-3 disabled:opacity-50",
        )}
      >
        {icon}
        <span className="min-w-0 flex-1 truncate">{label}</span>
        {sendingTo === channelId ? (
          <Loader2 aria-hidden className="size-4 shrink-0 animate-spin text-ink-muted" />
        ) : (
          <Send aria-hidden className="size-4 shrink-0 text-ink-muted" />
        )}
      </button>
    </li>
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Forward message" className="max-w-md">
        <p className="mt-2 line-clamp-3 rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm text-ink-secondary">
          {message.content || "(attachment only)"}
        </p>
        <input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search channels and DMs"
          aria-label="Search forward targets"
          className="mt-3 h-10 w-full rounded-lg border border-border bg-surface-1 px-3 text-sm outline-none focus:border-primary"
        />

        <div className="mt-3 max-h-80 overflow-y-auto">
          {channels.length > 0 && (
            <>
              <p className="px-2 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">
                {serverDetail?.server.name ?? "This server"}
              </p>
              <ul>
                {channels.map((channel) => (
                  <Row
                    key={channel.id}
                    channelId={channel.id}
                    to={`/servers/${channel.serverId}/channels/${channel.id}`}
                    icon={<Hash aria-hidden className="size-4 shrink-0 text-ink-muted" />}
                    label={channel.name ?? "channel"}
                  />
                ))}
              </ul>
            </>
          )}

          {dms.length > 0 && (
            <>
              <p className="px-2 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">
                Direct messages
              </p>
              <ul>
                {dms.map((conversation) => {
                  const other = otherParticipants(conversation, selfId)[0];
                  const name = conversationName(conversation, selfId);
                  return (
                    <Row
                      key={conversation.id}
                      channelId={conversation.id}
                      to={`/dms/${conversation.id}`}
                      icon={
                        conversation.type === "group_dm" || !other ? (
                          <Users aria-hidden className="size-4 shrink-0 text-ink-muted" />
                        ) : (
                          <Avatar user={other} className="size-6 shrink-0" />
                        )
                      }
                      label={name}
                    />
                  );
                })}
              </ul>
            </>
          )}

          {channels.length === 0 && dms.length === 0 && (
            <p className="py-8 text-center text-sm text-ink-muted">
              Nowhere to forward this to yet.
            </p>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
