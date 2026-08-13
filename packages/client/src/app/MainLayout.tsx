import { Navigate, Outlet, useMatch, useOutletContext, useParams } from "react-router-dom";
import { Loader2 } from "lucide-react";
import { PanelShell } from "./PanelShell";
import { DmSidebar } from "../features/dms/DmSidebar";
import { ChannelSidebar } from "../features/channels/ChannelSidebar";
import { MemberList } from "../features/members/MemberList";
import { ChatView } from "../features/chat/ChatView";
import { useServerDetail } from "../features/servers/queries";
import type { ServerDetail } from "../features/servers/api";
import { t } from "../lib/i18n";

/**
 * Shared shell (server rail + sidebar + optional aside) for home and server
 * routes alike, so switching between them swaps content in place instead of
 * remounting the whole panel.
 */
export function MainLayout() {
  const serverMatch = useMatch("/servers/:serverId/*");
  const serverId = serverMatch?.params.serverId;
  const channelId = useMatch("/servers/:serverId/channels/:channelId")?.params.channelId;
  const { data: detail, isLoading, error } = useServerDetail(serverId);

  if (!serverId) {
    return (
      <PanelShell sidebar={<DmSidebar />}>
        <Outlet />
      </PanelShell>
    );
  }

  if (isLoading) {
    return (
      <PanelShell sidebar={<div className="w-60 shrink-0 bg-surface-1" />}>
        <div className="flex flex-1 items-center justify-center bg-surface-2">
          <Loader2 aria-hidden className="size-6 animate-spin text-ink-muted" />
        </div>
      </PanelShell>
    );
  }

  if (!detail) {
    return (
      <PanelShell sidebar={<div className="w-60 shrink-0 bg-surface-1" />}>
        <div className="flex flex-1 flex-col items-center justify-center gap-2 bg-surface-2 p-6 text-center">
          <p className="font-semibold">{t("serverView.couldntLoadThisServer")}</p>
          <p className="text-sm text-ink-secondary">
            {error instanceof Error ? error.message : "It may have been deleted."}
          </p>
        </div>
      </PanelShell>
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
          activeChannelId={channelId}
        />
      }
      aside={
        <MemberList server={detail.server} roles={detail.roles} members={detail.members} />
      }
    >
      <Outlet context={detail} />
    </PanelShell>
  );
}

/** Leaf content for a server route, rendered via Outlet context from MainLayout. */
export function ServerChannelContent() {
  const detail = useOutletContext<ServerDetail>();
  const { serverId, channelId } = useParams();
  const textChannels = detail.channels
    .filter((c) => c.type === "text")
    .sort((a, b) => a.position - b.position);
  const channel = channelId ? detail.channels.find((c) => c.id === channelId) : undefined;

  if (!channel && textChannels.length > 0) {
    return <Navigate to={`/servers/${serverId}/channels/${textChannels[0]!.id}`} replace />;
  }

  return channel ? (
    <ChatView key={channel.id} channel={channel} members={detail.members} />
  ) : (
    <div className="flex flex-1 items-center justify-center bg-surface-2 p-6 text-center text-sm text-ink-secondary">
      {t("serverView.noTextChannelsYetCreateOne")}
    </div>
  );
}
