import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Crown, Gavel, MessageSquare, Pencil, UserRound, UserX } from "lucide-react";
import {
  Permissions,
  hasPermission,
  type Role,
  type Server,
  type ServerMember,
} from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { Avatar } from "../../components/Avatar";
import { DeviceIndicators } from "../../components/DeviceIndicators";
import { BotTag } from "../../components/BotTag";
import { ActivityStatus } from "../../components/ActivityStatus";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { TextField } from "../../components/ui/TextField";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "../../components/ui/DropdownMenu";
import { useAuthStore } from "../../stores/auth";
import { ProfileDialog } from "../profile/ProfileDialog";
import { useMyPermissions, serverKeys } from "../servers/queries";
import { createDm } from "../dms/api";
import { upsertConversation } from "../dms/queries";
import {
  assignRole,
  banMember,
  kickMember,
  setNickname,
  unassignRole,
} from "../roles/api";
import { t } from "../../lib/i18n";

interface MemberListProps {
  server: Server;
  roles: Role[];
  members: ServerMember[];
}


function memberColor(member: ServerMember, roles: Role[]): string | undefined {
  const colored = roles
    .filter((r) => member.roleIds.includes(r.id) && r.color !== 0)
    .sort((a, b) => b.position - a.position)[0];
  return colored ? `#${colored.color.toString(16).padStart(6, "0")}` : undefined;
}

type PendingAction =
  | { kind: "kick"; member: ServerMember }
  | { kind: "ban"; member: ServerMember }
  | { kind: "nickname"; member: ServerMember };

function MemberRow({
  member,
  server,
  roles,
  onAction,
}: {
  member: ServerMember;
  server: Server;
  roles: Role[];
  onAction: (action: PendingAction) => void;
}) {
  const selfId = useAuthStore((s) => s.user?.id);
  const navigate = useNavigate();
  const client = useQueryClient();
  const [profileOpen, setProfileOpen] = useState(false);
  const { data: perms } = useMyPermissions(server.id);

  const name = member.nickname ?? member.user.displayName;
  const offline = member.user.status === "offline";
  const isSelf = member.userId === selfId;
  const isOwner = member.userId === server.ownerId;

  const can = (p: bigint) => perms !== undefined && hasPermission(perms, p);
  const canNickname = isSelf || can(Permissions.MANAGE_NICKNAMES);
  const canRoles = can(Permissions.MANAGE_ROLES);
  const canKick = can(Permissions.KICK_MEMBERS) && !isSelf && !isOwner;
  const canBan = can(Permissions.BAN_MEMBERS) && !isSelf && !isOwner;
  const assignableRoles = roles
    .filter((r) => r.name !== "@everyone")
    .sort((a, b) => b.position - a.position);

  const dmMutation = useMutation({
    mutationFn: () => createDm([member.userId]),
    onSuccess: (conversation) => {
      upsertConversation(client, conversation);
      navigate(`/dms/${conversation.id}`);
    },
  });

  const roleMutation = useMutation({
    mutationFn: ({ roleId, assign }: { roleId: string; assign: boolean }) =>
      assign
        ? assignRole(server.id, member.userId, roleId)
        : unassignRole(server.id, member.userId, roleId),
    onSettled: () =>
      client.invalidateQueries({ queryKey: serverKeys.detail(server.id) }),
  });

  return (
    <li>
      <DropdownMenu>
        <DropdownMenuTrigger
          className={cn(
            "flex w-full items-center gap-2.5 rounded-lg px-2 py-2 text-left transition-colors hover:bg-surface-2 data-[state=open]:bg-surface-2 md:py-1.5",
            offline && "opacity-50",
          )}
        >
          <Avatar user={member.user} className="size-8" />
          <span className="min-w-0 flex-1">
            <span className="flex min-w-0 items-center gap-1.5">
              <span
                className="truncate text-sm font-medium"
                style={{ color: memberColor(member, roles) }}
              >
                {name}
              </span>
              {member.user.bot ? <BotTag /> : null}
            </span>
            <span className="flex min-w-0 items-center gap-1.5 text-xs text-ink-muted">
              <span className="truncate">@{member.user.username}</span>
              <DeviceIndicators status={member.user.status} devices={member.user.devices} />
            </span>
            <ActivityStatus activities={member.user.activities} className="mt-0.5" />
          </span>
          {isOwner && (
            <Crown
              aria-label={t("memberList.serverOwner")}
              className="size-3.5 shrink-0 text-warning"
            />
          )}
        </DropdownMenuTrigger>
        <DropdownMenuContent side="left" align="start">
          <DropdownMenuLabel>@{member.user.username}</DropdownMenuLabel>
          <DropdownMenuItem onSelect={() => setProfileOpen(true)}>
            <UserRound aria-hidden className="size-4" />
            {t("memberList.viewProfile")}
          </DropdownMenuItem>
          {!isSelf && (
            <DropdownMenuItem onSelect={() => dmMutation.mutate()}>
              <MessageSquare aria-hidden className="size-4" />
              {t("memberList.message")}
            </DropdownMenuItem>
          )}
          {canNickname && (
            <DropdownMenuItem onSelect={() => onAction({ kind: "nickname", member })}>
              <Pencil aria-hidden className="size-4" />
              {t("memberList.changeNickname")}
            </DropdownMenuItem>
          )}
          {canRoles && assignableRoles.length > 0 && (
            <DropdownMenuSub>
              <DropdownMenuSubTrigger>{t("memberList.roles")}</DropdownMenuSubTrigger>
              <DropdownMenuSubContent>
                {assignableRoles.map((role) => (
                  <DropdownMenuCheckboxItem
                    key={role.id}
                    checked={member.roleIds.includes(role.id)}
                    onCheckedChange={(checked) =>
                      roleMutation.mutate({ roleId: role.id, assign: checked === true })
                    }
                  >
                    <span
                      className="size-2.5 shrink-0 rounded-full"
                      style={{
                        backgroundColor:
                          role.color !== 0
                            ? `#${role.color.toString(16).padStart(6, "0")}`
                            : "var(--oc-ink-muted)",
                      }}
                    />
                    {role.name}
                  </DropdownMenuCheckboxItem>
                ))}
              </DropdownMenuSubContent>
            </DropdownMenuSub>
          )}
          {(canKick || canBan) && <DropdownMenuSeparator />}
          {canKick && (
            <DropdownMenuItem danger onSelect={() => onAction({ kind: "kick", member })}>
              <UserX aria-hidden className="size-4" />
              {t("memberList.kickName", { name })}
            </DropdownMenuItem>
          )}
          {canBan && (
            <DropdownMenuItem danger onSelect={() => onAction({ kind: "ban", member })}>
              <Gavel aria-hidden className="size-4" />
              {t("memberList.banName", { name })}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {profileOpen && (
        <ProfileDialog
          user={member.user}
          open={profileOpen}
          onOpenChange={setProfileOpen}
        />
      )}
    </li>
  );
}

function MemberActionDialogs({
  action,
  server,
  onClose,
}: {
  action: PendingAction | null;
  server: Server;
  onClose: () => void;
}) {
  const selfId = useAuthStore((s) => s.user?.id);
  const client = useQueryClient();
  const [reason, setReason] = useState("");
  const [nickname, setNicknameValue] = useState(
    action?.kind === "nickname" ? (action.member.nickname ?? "") : "",
  );

  const invalidate = () =>
    client.invalidateQueries({ queryKey: serverKeys.detail(server.id) });

  const kickMutation = useMutation({
    mutationFn: (userId: string) => kickMember(server.id, userId),
    onSuccess: () => {
      invalidate();
      onClose();
    },
  });
  const banMutation = useMutation({
    mutationFn: (userId: string) =>
      banMember(server.id, userId, reason.trim() || undefined),
    onSuccess: () => {
      invalidate();
      setReason("");
      onClose();
    },
  });
  const nicknameMutation = useMutation({
    mutationFn: (userId: string) =>
      setNickname(
        server.id,
        userId === selfId ? "@me" : userId,
        nickname.trim() || null,
      ),
    onSuccess: () => {
      invalidate();
      setNicknameValue("");
      onClose();
    },
  });

  if (!action) return null;
  const displayName = action.member.nickname ?? action.member.user.displayName;

  return (
    <>
      <ConfirmDialog
        open={action.kind === "kick"}
        onOpenChange={(open) => !open && onClose()}
        title={`Kick ${displayName}?`}
        description={t("memberList.theyCanRejoinWithANew")}
        confirmLabel={t("memberList.kick")}
        danger
        loading={kickMutation.isPending}
        error={kickMutation.error?.message}
        onConfirm={() => kickMutation.mutate(action.member.userId)}
      />
      <ConfirmDialog
        open={action.kind === "ban"}
        onOpenChange={(open) => !open && onClose()}
        title={`Ban ${displayName}?`}
        description={t("memberList.theyWontBeAbleToRejoin")}
        confirmLabel={t("memberList.ban")}
        danger
        loading={banMutation.isPending}
        error={banMutation.error?.message}
        onConfirm={() => banMutation.mutate(action.member.userId)}
      >
        <TextField
          label={t("memberList.reasonOptional")}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          maxLength={512}
        />
      </ConfirmDialog>
      <ConfirmDialog
        open={action.kind === "nickname"}
        onOpenChange={(open) => !open && onClose()}
        title={`Change nickname`}
        description={`New server nickname for ${action.member.user.displayName}. Leave empty to clear.`}
        confirmLabel={t("common.save")}
        loading={nicknameMutation.isPending}
        error={nicknameMutation.error?.message}
        onConfirm={() => nicknameMutation.mutate(action.member.userId)}
      >
        <TextField
          label={t("memberList.nickname")}
          value={nickname}
          onChange={(e) => setNicknameValue(e.target.value)}
          placeholder={action.member.nickname ?? ""}
          maxLength={32}
          autoFocus
        />
      </ConfirmDialog>
    </>
  );
}

/** Right column: members grouped into online (incl. idle/dnd) and offline. */
export function MemberList({ server, roles, members }: MemberListProps) {
  const [action, setAction] = useState<PendingAction | null>(null);

  const online = members.filter((m) => m.user.status !== "offline");
  const offline = members.filter((m) => m.user.status === "offline");
  const byName = (a: ServerMember, b: ServerMember) =>
    (a.nickname ?? a.user.displayName).localeCompare(b.nickname ?? b.user.displayName);

  const group = (list: ServerMember[]) =>
    list
      .sort(byName)
      .map((m) => (
        <MemberRow
          key={m.id}
          member={m}
          server={server}
          roles={roles}
          onAction={setAction}
        />
      ));

  return (
    <aside
      aria-label={t("memberList.members")}
      className="w-60 shrink-0 overflow-y-auto bg-surface-1 p-3 lg:w-56"
    >
      {online.length > 0 && (
        <>
          <h3 className="px-2 pb-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">
            {t("memberList.onlineCount", { count: online.length })}
          </h3>
          <ul>{group(online)}</ul>
        </>
      )}
      {offline.length > 0 && (
        <>
          <h3 className="px-2 pb-1 pt-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">
            {t("memberList.offlineCount", { count: offline.length })}
          </h3>
          <ul>{group(offline)}</ul>
        </>
      )}
      <MemberActionDialogs
        key={action ? `${action.kind}:${action.member.id}` : "none"}
        action={action}
        server={server}
        onClose={() => setAction(null)}
      />
    </aside>
  );
}
