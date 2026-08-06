import { useMemo, useRef, useState } from "react";
import { Navigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { ImagePlus, Loader2, Phone, Trash2, UserPlus, Video } from "lucide-react";
import type { Channel, Conversation, ServerMember } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { api } from "../../lib/api";
import { useAuthStore } from "../../stores/auth";
import { Avatar } from "../../components/Avatar";
import { ChatView, type HeaderMenuItem } from "../chat/ChatView";
import { uploadImage } from "../uploads/api";
import { EncryptionBadge } from "../e2ee/EncryptionBadge";
import { PlaintextNotice } from "../e2ee/PlaintextNotice";
import { VerificationNotice } from "../e2ee/VerificationNotice";
import { callActions, useCallStore } from "../voice/callStore";
import { useOtherDeviceIn, voiceActions } from "../voice/store";
import { conversationToChannel, dmKeys, useConversations } from "./queries";
import { DmIntro } from "./DmIntro";
import { NewDmDialog } from "./NewDmDialog";
import { ActivityStatus } from "../../components/ActivityStatus";

const headerButtonClass =
  "rounded-lg p-2 transition-colors disabled:pointer-events-none disabled:opacity-40";

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
        headerButtonClass,
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
  const [backgroundBusy, setBackgroundBusy] = useState(false);
  const backgroundInput = useRef<HTMLInputElement>(null);
  const queryClient = useQueryClient();
  const currentCall = useCallStore((s) => s.current);
  const onCall = currentCall?.channelId === channelId;

  const conversation = conversations?.find((c) => c.id === channelId);

  /** Apply the server's answer to the sidebar cache so the pane re-renders. */
  const applyBackground = (url: string | null) => {
    queryClient.setQueryData<Conversation[]>(dmKeys.list, (list) =>
      list?.map((c) => (c.id === channelId ? { ...c, backgroundUrl: url } : c)),
    );
  };

  const setBackground = async (file: File) => {
    setBackgroundBusy(true);
    try {
      const url = await uploadImage(file, "chat-background");
      const channel = await api<Channel>(`/channels/${channelId}/background`, {
        method: "PUT",
        json: { url },
      });
      // The notice about it is written by the server, on the same request -
      // the others find out from something they can trust rather than from a
      // sentence this client typed into the conversation.
      applyBackground(channel.backgroundUrl);
    } catch {
      // Keep the old background; an upload or save failure is not worth a dialog.
    } finally {
      setBackgroundBusy(false);
    }
  };

  const removeBackground = async () => {
    setBackgroundBusy(true);
    try {
      const channel = await api<Channel>(`/channels/${channelId}/background`, {
        method: "PUT",
        json: { url: null },
      });
      applyBackground(channel.backgroundUrl);
    } finally {
      setBackgroundBusy(false);
    }
  };

  // Everyone but the viewer - who the conversation is *with*.
  const others = useMemo(
    () => (conversation?.participants ?? []).filter((u) => u.id !== selfId),
    [conversation, selfId],
  );

  const channel: Channel | null = useMemo(
    () => (conversation ? conversationToChannel(conversation, selfId) : null),
    [conversation, selfId],
  );

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

  if (!conversation || !channel) return <Navigate to="/app" replace />;

  const headerActions = (
    <>
      <HeaderButton
        label={
          otherDevice
            ? "On this call on another device - click to disconnect it"
            : onCall
              ? "Already on this call"
              : "Start voice call"
        }
        // Glowing means the call is up elsewhere on your account, and the button
        // becomes the way to hang that device up - so it must stay clickable
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
      {/* The background moved into the menu, which closes on pick - so the
          upload needs somewhere of its own to say it is still going. */}
      {backgroundBusy && (
        <span role="status" aria-label="Saving chat background" className="p-2 text-ink-muted">
          <Loader2 aria-hidden className="size-4 animate-spin" />
        </span>
      )}
    </>
  );

  // Everything that isn't a call goes behind the header's one menu: named,
  // rather than a row of anonymous icons, and the background's remove is a
  // deliberate second tap because it changes the room for everyone in it.
  const headerMenu: HeaderMenuItem[] = [
    ...(conversation.type === "group_dm"
      ? [{ label: "Add people", icon: UserPlus, onSelect: () => setAddOpen(true) }]
      : []),
    {
      label: conversation.backgroundUrl ? "Change background" : "Set chat background",
      icon: ImagePlus,
      disabled: backgroundBusy,
      onSelect: () => backgroundInput.current?.click(),
    },
    ...(conversation.backgroundUrl
      ? [
          {
            label: "Remove background for everyone",
            icon: Trash2,
            danger: true,
            disabled: backgroundBusy,
            onSelect: () => void removeBackground(),
          },
        ]
      : []),
  ];

  return (
    <>
      <input
        ref={backgroundInput}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) void setBackground(file);
          event.target.value = "";
        }}
      />
      <ChatView
        key={channel.id}
        channel={channel}
        members={members}
        headerActions={headerActions}
        headerMenu={headerMenu}
        headerIcon={
          others[0] ? (
            <Avatar user={others[0]} status={others.length === 1 ? others[0].status : undefined} className="size-7" />
          ) : undefined
        }
        headerSubtitle={
          others.length === 1 ? <ActivityStatus activities={others[0]?.activities} /> : undefined
        }
        encryptionBadge={
          others.length > 0 ? (
            <EncryptionBadge
              peers={others}
              groupName={conversation.type === "group_dm" ? channel.name : null}
              channelId={channel.id}
            />
          ) : undefined
        }
        encryptionNotice={
          <>
            <PlaintextNotice channelId={channel.id} peers={others} />
            <VerificationNotice channelId={channel.id} peers={others} />
          </>
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
