import { useState } from "react";
import { Link, useMatch, useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import type { Server } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { useStillFrame } from "../../lib/stillFrame";
import { LogoMark } from "../../components/LogoMark";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { ContextMenu, ContextMenuTrigger } from "../../components/ui/ContextMenu";
import { TextField } from "../../components/ui/TextField";
import { Tooltip } from "../../components/ui/Tooltip";
import { UnreadBadge } from "../../components/UnreadBadge";
import { useAuthStore } from "../../stores/auth";
import { useDmUnreadTotal, useServerUnread } from "../../stores/unread";
import { CreateChannelDialog } from "../channels/CreateChannelDialog";
import { EventsDialog } from "../events/EventsDialog";
import { InviteDialog } from "../channels/InviteDialog";
import { setNickname } from "../roles/api";
import { UserSettingsDialog } from "../settings/UserSettingsDialog";
import { leaveServer } from "./api";
import { useServerDetail, useServers, serverKeys } from "./queries";
import { initials } from "./ServerIcon";
import { AddServerDialog } from "./AddServerDialog";
import { ServerContextMenu } from "./ServerContextMenu";
import { ServerSettingsDialog } from "./ServerSettingsDialog";
import { t } from "../../lib/i18n";


type ServerDialog =
  | "invite"
  | "settings"
  | "create-channel"
  | "create-category"
  | "events"
  | "nickname"
  | "privacy"
  | "leave"
  | null;


function NicknameDialog({
  server,
  open,
  onClose,
}: {
  server: Server;
  open: boolean;
  onClose: () => void;
}) {
  const client = useQueryClient();
  const selfId = useAuthStore((s) => s.user?.id);
  const { data: detail } = useServerDetail(open ? server.id : undefined);
  const current = detail?.members.find((m) => m.userId === selfId)?.nickname ?? "";
  const [value, setValue] = useState<string | null>(null);
  const nickname = value ?? current;

  const mutation = useMutation({
    mutationFn: () => setNickname(server.id, "@me", nickname.trim() || null),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: serverKeys.detail(server.id) });
      setValue(null);
      onClose();
    },
  });

  return (
    <ConfirmDialog
      open={open}
      onOpenChange={(next) => !next && onClose()}
      title={`Your profile in ${server.name}`}
      description={t("serverRail.membersOfThisServerSeeThis")}
      confirmLabel={t("common.save")}
      loading={mutation.isPending}
      error={mutation.error?.message}
      onConfirm={() => mutation.mutate()}
    >
      <TextField
        label={t("serverRail.nickname")}
        value={nickname}
        onChange={(e) => setValue(e.target.value)}
        maxLength={32}
        autoFocus
      />
    </ConfirmDialog>
  );
}

function ServerRailIcon({ server, active }: { server: Server; active: boolean }) {
  const { unread, mentionCount } = useServerUnread(server.id);
  const showUnread = unread && !active;
  const stillIcon = useStillFrame(server.iconUrl);
  const client = useQueryClient();
  const navigate = useNavigate();
  const [dialog, setDialog] = useState<ServerDialog>(null);
  const close = () => setDialog(null);

  const leave = useMutation({
    mutationFn: () => leaveServer(server.id),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: serverKeys.list });
      client.removeQueries({ queryKey: serverKeys.detail(server.id) });
      close();
      if (active) navigate("/app");
    },
  });

  return (
    <>
      <ContextMenu>
        <ContextMenuTrigger asChild>
          {/* The trigger needs one element that owns the whole icon's hit area. */}
          <div className="w-full shrink-0">
            <Tooltip label={server.name}>
              <Link
                to={`/servers/${server.id}`}
                aria-current={active ? "page" : undefined}
                className="group relative flex justify-center"
              >
                {/* Active / hover / unread accent bar on the rail edge */}
                <span
                  className={cn(
                    "absolute -left-3 top-1/2 w-[3px] -translate-y-1/2 bg-primary transition-all",
                    active ? "h-9" : showUnread ? "h-2.5 bg-ink" : "h-0 group-hover:h-5",
                  )}
                />
                {mentionCount > 0 && !active && (
                  <span className="absolute -bottom-0.5 -right-0.5 z-10 min-w-[1.1rem] rounded-full border-2 border-surface-0 bg-danger px-1 py-0.5 text-center text-[10px] font-bold leading-none text-white">
                    {mentionCount > 99 ? "99+" : mentionCount}
                  </span>
                )}
                {server.iconUrl ? (
                  <img
                    // An animated icon plays only for the server you are in;
                    // the rest hold their first frame. Null whenever a still
                    // could not be taken, which lands back on the live URL.
                    src={(!active && stillIcon) || server.iconUrl}
                    alt={server.name}
                    className={cn(
                      "size-12 rounded-squircle object-cover ring-2 transition-all",
                      active ? "ring-primary" : "ring-transparent group-hover:ring-border-strong",
                    )}
                  />
                ) : (
                  <span
                    className={cn(
                      "flex size-12 items-center justify-center rounded-squircle font-semibold transition-colors",
                      active
                        ? "bg-primary text-ink-on-primary"
                        : "bg-surface-3 text-ink-secondary group-hover:bg-surface-4 group-hover:text-ink",
                    )}
                  >
                    {initials(server.name)}
                  </span>
                )}
              </Link>
            </Tooltip>
          </div>
        </ContextMenuTrigger>

        <ServerContextMenu
          server={server}
          onInvite={() => setDialog("invite")}
          onCreateCategory={() => setDialog("create-category")}
          onCreateEvent={() => setDialog("events")}
          onSettings={() => setDialog("settings")}
          onCreateChannel={() => setDialog("create-channel")}
          onEditProfile={() => setDialog("nickname")}
          onPrivacy={() => setDialog("privacy")}
          onLeave={() => setDialog("leave")}
        />
      </ContextMenu>

      {/* Dialogs live outside the menu: its content unmounts when it closes. */}
      <InviteDialog
        serverId={server.id}
        open={dialog === "invite"}
        onOpenChange={(open) => !open && close()}
      />
      <CreateChannelDialog
        serverId={server.id}
        open={dialog === "create-channel"}
        onOpenChange={(open) => !open && close()}
      />
      <CreateChannelDialog
        serverId={server.id}
        only="category"
        open={dialog === "create-category"}
        onOpenChange={(open) => !open && close()}
      />
      {dialog === "events" && (
        <EventsDialog
          server={server}
          open
          startCreating
          onOpenChange={(open) => !open && close()}
        />
      )}
      {dialog === "settings" && <ServerSettingsDialogLoader server={server} onClose={close} />}
      <NicknameDialog server={server} open={dialog === "nickname"} onClose={close} />
      {dialog === "privacy" && (
        <UserSettingsDialog
          open
          initialSection="privacy"
          onOpenChange={(open) => !open && close()}
        />
      )}
      <ConfirmDialog
        open={dialog === "leave"}
        onOpenChange={(open) => !open && close()}
        title={`Leave ${server.name}?`}
        description={t("serverRail.youllNeedANewInviteTo")}
        confirmLabel={t("serverRail.leaveServer")}
        danger
        loading={leave.isPending}
        error={leave.error?.message}
        onConfirm={() => leave.mutate()}
      />
    </>
  );
}

/** ServerSettingsDialog needs the server's roles, which only the detail query has. */
function ServerSettingsDialogLoader({ server, onClose }: { server: Server; onClose: () => void }) {
  const { data: detail } = useServerDetail(server.id);
  if (!detail) return null;
  return (
    <ServerSettingsDialog
      server={detail.server}
      roles={detail.roles}
      open
      onOpenChange={(open) => !open && onClose()}
    />
  );
}

/** Far-left column: home, one icon per joined server, and the add button. */
export function ServerRail() {
  // Layout-level component: child-route params aren't visible via useParams.
  const serverId = useMatch("/servers/:serverId/*")?.params.serverId;
  const { data: servers } = useServers();
  const dmUnread = useDmUnreadTotal();
  const [addOpen, setAddOpen] = useState(false);

  return (
    <nav
      aria-label={t("serverRail.servers")}
      className="flex w-[72px] shrink-0 flex-col items-center gap-2 overflow-y-auto bg-surface-0 px-3 py-3"
    >
      <Tooltip label={t("serverRail.home")}>
        <Link to="/app" className="relative flex justify-center">
          <LogoMark className="size-12 transition-transform hover:scale-105" />
          <UnreadBadge
            count={dmUnread}
            label={t("serverRail.unreadDirectMessages")}
            className="absolute -bottom-0.5 -right-0.5 z-10 rounded-full border-2 border-surface-0"
          />
        </Link>
      </Tooltip>
      <div className="my-1 h-px w-8 shrink-0 rounded bg-border" />

      {servers?.map((server) => (
        <ServerRailIcon key={server.id} server={server} active={server.id === serverId} />
      ))}

      <Tooltip label={t("serverRail.addAServer")}>
        <button
          type="button"
          onClick={() => setAddOpen(true)}
          className="flex size-12 shrink-0 items-center justify-center rounded-squircle bg-surface-3 text-success transition-colors hover:bg-success hover:text-surface-0"
        >
          <Plus aria-hidden className="size-6" />
          <span className="sr-only">{t("serverRail.addAServer")}</span>
        </button>
      </Tooltip>

      <AddServerDialog open={addOpen} onOpenChange={setAddOpen} />
    </nav>
  );
}
