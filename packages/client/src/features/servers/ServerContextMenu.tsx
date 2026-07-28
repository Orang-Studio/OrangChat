import { useMutation } from "@tanstack/react-query";
import { useShallow } from "zustand/react/shallow";
import {
  BellOff,
  BellRing,
  CalendarPlus,
  Check,
  Copy,
  FolderPlus,
  IdCard,
  LogOut,
  Plus,
  Settings,
  Sliders,
  UserPlus,
} from "lucide-react";
import { Permissions, hasPermission, type Server } from "@orangchat/shared";
import {
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
} from "../../components/ui/ContextMenu";
import { unreadActions, useUnreadStore } from "../../stores/unread";
import { markChannelRead } from "../unread/api";
import { useMyPermissions } from "./queries";
import {
  LEVEL_LABEL,
  MUTE_DURATIONS,
  serverNotificationActions,
  useServerNotificationPrefs,
  type NotificationLevel,
} from "./notificationPrefs";

export interface ServerContextMenuProps {
  server: Server;
  onInvite: () => void;
  onCreateCategory: () => void;
  onCreateEvent: () => void;
  onSettings: () => void;
  onCreateChannel: () => void;
  onEditProfile: () => void;
  onPrivacy: () => void;
  onLeave: () => void;
}

/**
 * Right-click menu for a server on the rail. Mounted only while the menu is
 * open (Radix portals its content), so the permission fetch it needs happens on
 * demand instead of once per server icon at boot.
 */
export function ServerContextMenu({
  server,
  onInvite,
  onCreateCategory,
  onCreateEvent,
  onSettings,
  onCreateChannel,
  onEditProfile,
  onPrivacy,
  onLeave,
}: ServerContextMenuProps) {
  const { data: perms } = useMyPermissions(server.id);
  const prefs = useServerNotificationPrefs(server.id);
  // useShallow: the selector builds a fresh array, and an unstable snapshot
  // would re-render forever.
  const unreadChannelIds = useUnreadStore(
    useShallow((s) =>
      Object.entries(s.channels)
        .filter(([, c]) => c.serverId === server.id && c.unread)
        .map(([id]) => id),
    ),
  );

  const can = (p: bigint) => perms !== undefined && hasPermission(perms, p);
  const showSettings =
    can(Permissions.MANAGE_SERVER) ||
    can(Permissions.MANAGE_ROLES) ||
    can(Permissions.BAN_MEMBERS) ||
    can(Permissions.MANAGE_EXPRESSIONS);

  const markRead = useMutation({
    mutationFn: async () => {
      await Promise.all(unreadChannelIds.map((id) => markChannelRead(id)));
    },
    onSuccess: () => unreadChannelIds.forEach((id) => unreadActions.clear(id)),
  });

  const muted = prefs.mutedUntil !== null;

  return (
    <ContextMenuContent className="w-60">
      <ContextMenuItem disabled={unreadChannelIds.length === 0} onSelect={() => markRead.mutate()}>
        <Check aria-hidden className="size-4" />
        Mark As Read
      </ContextMenuItem>

      <ContextMenuSeparator />

      <ContextMenuItem disabled={!can(Permissions.MANAGE_INVITES)} onSelect={onInvite}>
        <UserPlus aria-hidden className="size-4" />
        Invite to Server
      </ContextMenuItem>

      <ContextMenuSeparator />

      {muted ? (
        <ContextMenuItem onSelect={() => serverNotificationActions.unmute(server.id)}>
          <BellRing aria-hidden className="size-4" />
          Unmute Server
        </ContextMenuItem>
      ) : (
        <ContextMenuSub>
          <ContextMenuSubTrigger>
            <BellOff aria-hidden className="size-4" />
            Mute Server
          </ContextMenuSubTrigger>
          <ContextMenuSubContent>
            {MUTE_DURATIONS.map(({ label, ms }) => (
              <ContextMenuItem
                key={label}
                onSelect={() => serverNotificationActions.mute(server.id, ms)}
              >
                {label}
              </ContextMenuItem>
            ))}
          </ContextMenuSubContent>
        </ContextMenuSub>
      )}

      <ContextMenuSub>
        <ContextMenuSubTrigger>
          <span className="flex flex-col">
            Notification Settings
            <span className="text-xs font-normal text-ink-muted">{LEVEL_LABEL[prefs.level]}</span>
          </span>
        </ContextMenuSubTrigger>
        <ContextMenuSubContent>
          {(Object.keys(LEVEL_LABEL) as NotificationLevel[]).map((level) => (
            <ContextMenuItem
              key={level}
              onSelect={() => serverNotificationActions.setLevel(server.id, level)}
            >
              <span className="w-4 shrink-0">
                {prefs.level === level && <Check aria-hidden className="size-4" />}
              </span>
              {LEVEL_LABEL[level]}
            </ContextMenuItem>
          ))}
        </ContextMenuSubContent>
      </ContextMenuSub>

      <ContextMenuSeparator />

      <ContextMenuItem disabled={!showSettings} onSelect={onSettings}>
        <Settings aria-hidden className="size-4" />
        Server Settings
      </ContextMenuItem>
      <ContextMenuItem onSelect={onPrivacy}>
        <Sliders aria-hidden className="size-4" />
        Privacy Settings
      </ContextMenuItem>
      <ContextMenuItem onSelect={onEditProfile}>
        <IdCard aria-hidden className="size-4" />
        Edit Per-server Profile
      </ContextMenuItem>

      <ContextMenuSeparator />

      <ContextMenuItem disabled={!can(Permissions.MANAGE_CHANNELS)} onSelect={onCreateChannel}>
        <Plus aria-hidden className="size-4" />
        Create Channel
      </ContextMenuItem>
      <ContextMenuItem disabled={!can(Permissions.MANAGE_CHANNELS)} onSelect={onCreateCategory}>
        <FolderPlus aria-hidden className="size-4" />
        Create Category
      </ContextMenuItem>
      <ContextMenuItem onSelect={onCreateEvent}>
        <CalendarPlus aria-hidden className="size-4" />
        {can(Permissions.MANAGE_EVENTS) ? "Create Event" : "View Events"}
      </ContextMenuItem>

      <ContextMenuSeparator />

      <ContextMenuItem danger onSelect={onLeave}>
        <LogOut aria-hidden className="size-4" />
        Leave Server
      </ContextMenuItem>

      <ContextMenuSeparator />

      <ContextMenuItem onSelect={() => void navigator.clipboard?.writeText(server.id)}>
        <Copy aria-hidden className="size-4" />
        Copy Server ID
      </ContextMenuItem>
    </ContextMenuContent>
  );
}
