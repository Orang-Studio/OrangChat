import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ChevronDown, Hash, MicOff, Plus, Settings, UserPlus, Volume2 } from "lucide-react";
import {
  Permissions,
  hasPermission,
  type Channel,
  type Role,
  type Server,
  type ServerMember,
} from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { useChannelUnread } from "../../stores/unread";
import { Avatar } from "../../components/Avatar";
import { UserFooter } from "../../components/UserFooter";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "../../components/ui/DropdownMenu";
import { useMyPermissions } from "../servers/queries";
import { ServerSettingsDialog } from "../servers/ServerSettingsDialog";
import { useVoiceChannels } from "../voice/useVoiceChannels";
import { useOtherDeviceIn, useVoiceParticipants, useVoiceStore, voiceActions } from "../voice/store";
import { VoicePanel } from "../voice/VoicePanel";
import { CreateChannelDialog } from "./CreateChannelDialog";
import { InviteDialog } from "./InviteDialog";

interface ChannelSidebarProps {
  server: Server;
  channels: Channel[];
  members: ServerMember[];
  roles: Role[];
}

function TextChannelLink({ channel, active }: { channel: Channel; active: boolean }) {
  const { unread, mentionCount } = useChannelUnread(channel.id);
  const showUnread = unread && !active;

  return (
    <Link
      to={`/servers/${channel.serverId}/channels/${channel.id}`}
      aria-current={active ? "page" : undefined}
      className={cn(
        "group relative flex items-center gap-2 rounded-md px-2 py-2.5 text-sm font-medium transition-colors md:py-1.5",
        active
          ? "bg-primary-soft text-ink"
          : showUnread
            ? "text-ink hover:bg-surface-2"
            : "text-ink-secondary hover:bg-surface-2 hover:text-ink",
      )}
    >
      {showUnread && (
        <span
          aria-hidden
          className="absolute -left-1 top-1/2 h-2 w-1 -translate-y-1/2 rounded-full bg-ink"
        />
      )}
      <Hash
        aria-hidden
        className={cn("size-4 shrink-0", active ? "text-primary" : "text-ink-muted")}
      />
      <span className="truncate">{channel.name}</span>
      {mentionCount > 0 && !active && (
        <span className="ml-auto min-w-[1.25rem] shrink-0 rounded-full bg-danger px-1.5 py-0.5 text-center text-[11px] font-bold leading-none text-white">
          {mentionCount > 99 ? "99+" : mentionCount}
        </span>
      )}
    </Link>
  );
}

function VoiceChannelRow({
  channel,
  members,
  canConnect,
}: {
  channel: Channel;
  members: ServerMember[];
  canConnect: boolean;
}) {
  const participants = useVoiceParticipants(channel.id);
  const isHere = useVoiceStore((s) => s.session?.channelId === channel.id);
  const otherDevice = useOtherDeviceIn(channel.id);

  const resolve = (userId: string) => members.find((m) => m.userId === userId);

  return (
    <div>
      <button
        type="button"
        disabled={!canConnect}
        // Connected here on another device: the click hangs that one up rather
        // than joining a channel we are, from the account's point of view, in.
        onClick={() =>
          otherDevice
            ? voiceActions.disconnectDevice(otherDevice.sessionId)
            : void voiceActions.join(channel)
        }
        title={
          otherDevice
            ? `Connected on another device — click to disconnect it`
            : canConnect
              ? `Join ${channel.name}`
              : "Missing Connect permission"
        }
        className={cn(
          "flex w-full items-center gap-2 rounded-md px-2 py-2.5 text-sm font-medium transition-colors md:py-1.5",
          isHere
            ? "bg-primary-soft text-ink"
            : "text-ink-secondary hover:bg-surface-2 hover:text-ink",
          otherDevice && "shadow-[inset_0_0_0_1px_rgba(63,189,110,0.6)]",
          !canConnect && "cursor-not-allowed opacity-60",
        )}
      >
        <Volume2
          aria-hidden
          className={cn(
            "size-4 shrink-0",
            otherDevice ? "text-success" : isHere ? "text-primary" : "text-ink-muted",
          )}
        />
        <span className="truncate">{channel.name}</span>
        {participants.length > 0 && (
          <span className="ml-auto text-xs text-ink-muted">{participants.length}</span>
        )}
      </button>
      {participants.length > 0 && (
        <ul className="ml-6 space-y-0.5 py-0.5">
          {participants.map((p) => {
            const member = resolve(p.userId);
            const name = member
              ? (member.nickname ?? member.user.displayName)
              : "Someone";
            return (
              <li
                key={p.userId}
                className="flex items-center gap-2 rounded-lg px-2 py-1 text-sm text-ink-secondary"
              >
                <Avatar
                  user={member?.user ?? { displayName: name, avatarUrl: null }}
                  className="size-5"
                />
                <span className="min-w-0 flex-1 truncate">{name}</span>
                {p.screenSharing && (
                  <span
                    title={`${name} is sharing their screen`}
                    className="shrink-0 rounded border border-primary px-1 text-[10px] font-bold uppercase leading-tight text-primary"
                  >
                    Live
                  </span>
                )}
                {(p.muted || p.deafened) && (
                  <MicOff aria-hidden className="size-3.5 shrink-0 text-ink-muted" />
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** Middle column: server header menu, channel list, voice panel, user footer. */
export function ChannelSidebar({ server, channels, members, roles }: ChannelSidebarProps) {
  const { channelId } = useParams();
  const { data: perms } = useMyPermissions(server.id);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);

  const can = (p: bigint) => perms !== undefined && hasPermission(perms, p);
  const canInvite = can(Permissions.MANAGE_INVITES);
  const canManageChannels = can(Permissions.MANAGE_CHANNELS);
  const canConnect = can(Permissions.CONNECT);
  const showSettings =
    can(Permissions.MANAGE_SERVER) ||
    can(Permissions.MANAGE_ROLES) ||
    can(Permissions.BAN_MEMBERS) ||
    can(Permissions.MANAGE_EXPRESSIONS);

  const sorted = [...channels].sort((a, b) => a.position - b.position);
  const voiceChannelIds = useMemo(
    () => channels.filter((c) => c.type === "voice").map((c) => c.id),
    [channels],
  );
  useVoiceChannels(voiceChannelIds);

  return (
    <div className="flex w-60 shrink-0 flex-col bg-surface-1">
      {/* Server header with actions menu */}
      <DropdownMenu>
        <DropdownMenuTrigger className="flex h-12 items-center justify-between border-b border-border px-4 text-left font-semibold transition-colors hover:bg-surface-2">
          <span className="truncate">{server.name}</span>
          <ChevronDown aria-hidden className="size-4 shrink-0 text-ink-muted" />
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-52">
          {canInvite && (
            <DropdownMenuItem onSelect={() => setInviteOpen(true)}>
              <UserPlus aria-hidden className="size-4" />
              Invite people
            </DropdownMenuItem>
          )}
          {canManageChannels && (
            <DropdownMenuItem onSelect={() => setCreateOpen(true)}>
              <Plus aria-hidden className="size-4" />
              Create channel
            </DropdownMenuItem>
          )}
          {showSettings && (
            <DropdownMenuItem onSelect={() => setSettingsOpen(true)}>
              <Settings aria-hidden className="size-4" />
              Server settings
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      {/* Channels */}
      <nav aria-label="Channels" className="flex-1 space-y-0.5 overflow-y-auto p-2">
        <div className="flex items-center justify-between px-2 pb-1 pt-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">
            Channels
          </span>
          {canManageChannels && (
            <button
              type="button"
              onClick={() => setCreateOpen(true)}
              aria-label="Create channel"
              className="rounded p-0.5 text-ink-muted transition-colors hover:text-ink"
            >
              <Plus aria-hidden className="size-4" />
            </button>
          )}
        </div>
        {sorted.map((channel) =>
          channel.type === "voice" ? (
            <VoiceChannelRow
              key={channel.id}
              channel={channel}
              members={members}
              canConnect={canConnect}
            />
          ) : (
            <TextChannelLink
              key={channel.id}
              channel={channel}
              active={channel.id === channelId}
            />
          ),
        )}
        {sorted.length === 0 && (
          <p className="px-2 py-4 text-sm text-ink-muted">No channels yet.</p>
        )}
      </nav>

      <VoicePanel />
      <UserFooter />

      <InviteDialog serverId={server.id} open={inviteOpen} onOpenChange={setInviteOpen} />
      <CreateChannelDialog
        serverId={server.id}
        open={createOpen}
        onOpenChange={setCreateOpen}
      />
      <ServerSettingsDialog
        server={server}
        roles={roles}
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
      />
    </div>
  );
}
