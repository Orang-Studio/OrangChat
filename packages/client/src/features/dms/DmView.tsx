import { useMemo, useState } from "react";
import { Navigate, useParams } from "react-router-dom";
import { Loader2, Phone, UserPlus, Video } from "lucide-react";
import type { Channel, ServerMember } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { useAuthStore } from "../../stores/auth";
import { Avatar } from "../../components/Avatar";
import { ChatView } from "../chat/ChatView";
import { callActions, useCallStore } from "../voice/callStore";
import { useOtherDeviceIn, voiceActions } from "../voice/store";
import { conversationName, useConversations } from "./queries";
import { DmIntro } from "./DmIntro";
import { NewDmDialog } from "./NewDmDialog";

function HeaderButton({
  label,
  onClick,
  disabled,
  glow,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  /** This call is live on another of your devices. */
  glow?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
      className={cn(
        "rounded-lg p-2 transition-colors disabled:pointer-events-none disabled:opacity-40",
        glow
          ? "text-success shadow-[0_0_12px_rgba(63,189,110,0.6)] hover:bg-surface-3"
          : "text-ink-muted hover:bg-surface-3 hover:text-ink",
      )}
    >
      {children}
    </button>
  );
}

/** The `/dms/:channelId` pane - a conversation rendered through ChatView. */
export function DmView() {
  const { channelId } = useParams();
  const otherDevice = useOtherDeviceIn(channelId);
  const selfId = useAuthStore((s) => s.user?.id);
  const { data: conversations, isLoading } = useConversations();
  const [addOpen, setAddOpen] = useState(false);
  const currentCall = useCallStore((s) => s.current);
  const onCall = currentCall?.channelId === channelId;

  const conversation = conversations?.find((c) => c.id === channelId);

  // Everyone but the viewer - who the conversation is *with*.
  const others = useMemo(
    () => (conversation?.participants ?? []).filter((u) => u.id !== selfId),
    [conversation, selfId],
  );

  const channel: Channel | null = useMemo(() => {
    if (!conversation) return null;
    return {
      id: conversation.id,
      serverId: null,
      name: conversationName(conversation, selfId),
      type: conversation.type,
      topic: null,
      position: 0,
      parentCategoryId: null,
      // A DM has no moderator, so none of the channel settings apply: no NSFW
      // gate, no slowmode, and the voice fields are unused for a text-shaped
      // conversation. These are the "off" values the server also stores.
      nsfw: false,
      rateLimitPerUser: 0,
      userLimit: 0,
      bitrate: 64000,
    };
  }, [conversation, selfId]);

  // ChatView resolves typing/display names through ServerMember rows; wrap the
  // conversation's participants in that shape.
  const members: ServerMember[] = useMemo(
    () =>
      (conversation?.participants ?? []).map((user) => ({
        id: user.id,
        serverId: "",
        userId: user.id,
        nickname: null,
        // Timeouts are a server-moderation tool; a DM participant is never one.
        timedOutUntil: null,
        joinedAt: user.createdAt,
        roleIds: [],
        user,
      })),
    [conversation],
  );

  if (isLoading) {
    return (
      <div className="flex flex-1 items-center justify-center bg-surface-2">
        <Loader2 aria-hidden className="size-6 animate-spin text-ink-muted" />
      </div>
    );
  }

  if (!conversation || !channel) return <Navigate to="/" replace />;

  const headerActions = (
    <>
      <HeaderButton
        label={
          otherDevice
            ? "On this call on another device — click to disconnect it"
            : onCall
              ? "Already on this call"
              : "Start voice call"
        }
        // Glowing means the call is up elsewhere on your account, and the button
        // becomes the way to hang that device up — so it must stay clickable
        // even though `onCall` is false on this one.
        disabled={onCall && !otherDevice}
        glow={!!otherDevice}
        onClick={() =>
          otherDevice
            ? voiceActions.disconnectDevice(otherDevice.sessionId)
            : void callActions.start(channel)
        }
      >
        <Phone aria-hidden className="size-4" />
      </HeaderButton>
      <HeaderButton
        label={onCall ? "Already on this call" : "Start video call"}
        disabled={onCall}
        onClick={() => void callActions.start(channel, { video: true })}
      >
        <Video aria-hidden className="size-4" />
      </HeaderButton>
      {conversation.type === "group_dm" && (
        <HeaderButton label="Add people" onClick={() => setAddOpen(true)}>
          <UserPlus aria-hidden className="size-4" />
        </HeaderButton>
      )}
    </>
  );

  return (
    <>
      <ChatView
        key={channel.id}
        channel={channel}
        members={members}
        headerActions={headerActions}
        headerIcon={
          others[0] ? (
            <Avatar user={others[0]} status={others.length === 1 ? others[0].status : undefined} className="size-7" />
          ) : undefined
        }
        intro={
          <DmIntro
            participants={others}
            groupName={conversation.type === "group_dm" ? channel.name : null}
          />
        }
      />
      <NewDmDialog open={addOpen} onOpenChange={setAddOpen} addTo={conversation} />
    </>
  );
}
