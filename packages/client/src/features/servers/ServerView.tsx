import { Navigate, useParams } from "react-router-dom";
import { Loader2 } from "lucide-react";
import { PanelShell } from "../../app/PanelShell";
import { useServerDetail } from "./queries";
import { ChannelSidebar } from "../channels/ChannelSidebar";
import { MemberList } from "../members/MemberList";
import { ChatView } from "../chat/ChatView";

/**
 * Everything right of the server rail for one server: channel sidebar, chat,
 * member list. Without a channel in the URL, redirects to the first text channel.
 */
export function ServerView() {
  const { serverId, channelId } = useParams();
  const { data: detail, isLoading, error } = useServerDetail(serverId);

  if (isLoading) {
    return (
      <PanelShell sidebar={<div className="w-60 shrink-0 bg-surface-1" />}>
        <div className="flex flex-1 items-center justify-center bg-surface-2">
          <Loader2 aria-hidden className="size-6 animate-spin text-ink-muted" />
        </div>
      </PanelShell>
    );
  }

  // A failed background refresh is expected offline. Keep rendering the disk
  // snapshot whenever one exists; this screen is only terminal without data.
  if (!detail) {
    return (
      <PanelShell sidebar={<div className="w-60 shrink-0 bg-surface-1" />}>
        <div className="flex flex-1 flex-col items-center justify-center gap-2 bg-surface-2 p-6 text-center">
          <p className="font-semibold">Couldn't load this server</p>
          <p className="text-sm text-ink-secondary">
            {error instanceof Error ? error.message : "It may have been deleted."}
          </p>
        </div>
      </PanelShell>
    );
  }

  const textChannels = detail.channels
    .filter((c) => c.type === "text")
    .sort((a, b) => a.position - b.position);

  const channel = channelId
    ? detail.channels.find((c) => c.id === channelId)
    : undefined;

  // No channel selected (or it was deleted) → jump to the first text channel.
  if (!channel && textChannels.length > 0) {
    return (
      <Navigate to={`/servers/${serverId}/channels/${textChannels[0]!.id}`} replace />
    );
  }

  return (
    <PanelShell
      sidebar={
        <ChannelSidebar
          server={detail.server}
          channels={detail.channels}
          members={detail.members}
          roles={detail.roles}
        />
      }
      aside={
        <MemberList server={detail.server} roles={detail.roles} members={detail.members} />
      }
    >
      {channel ? (
        <ChatView key={channel.id} channel={channel} members={detail.members} />
      ) : (
        <div className="flex flex-1 items-center justify-center bg-surface-2 p-6 text-center text-sm text-ink-secondary">
          No text channels yet - create one from the server menu.
        </div>
      )}
    </PanelShell>
  );
}
