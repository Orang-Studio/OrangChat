import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Plus, Trash2 } from "lucide-react";
import {
  Permissions,
  hasPermission,
  parsePermissions,
  serializePermissions,
  type PermissionName,
  type Role,
  type Server,
} from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { Avatar } from "../../components/Avatar";
import { ImageField } from "../../components/ImageField";
import { Button } from "../../components/ui/Button";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/Tabs";
import { TextField } from "../../components/ui/TextField";
import { EmojiTab } from "../emojis/EmojiTab";
import { SoundTab } from "../soundboard/SoundTab";
import { AuditLogTab } from "./AuditLogTab";
import { useAuthStore } from "../../stores/auth";
import { createRole, deleteRole, listBans, unbanMember, updateRole } from "../roles/api";
import { deleteServer, updateServer } from "./api";
import { serverKeys, useMyPermissions } from "./queries";

interface ServerSettingsDialogProps {
  server: Server;
  roles: Role[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const PERMISSION_GROUPS: [string, PermissionName[]][] = [
  ["General", ["ADMINISTRATOR", "MANAGE_SERVER", "MANAGE_ROLES", "MANAGE_CHANNELS", "MANAGE_INVITES", "MANAGE_EXPRESSIONS", "MANAGE_EVENTS"]],
  ["Membership", ["KICK_MEMBERS", "BAN_MEMBERS", "MANAGE_NICKNAMES", "MANAGE_MESSAGES"]],
  ["Text", ["VIEW_CHANNEL", "SEND_MESSAGES", "EMBED_LINKS", "ATTACH_FILES", "ADD_REACTIONS", "MENTION_EVERYONE", "READ_MESSAGE_HISTORY"]],
  ["Voice", ["CONNECT", "SPEAK", "VIDEO", "SCREEN_SHARE", "MUTE_MEMBERS", "DEAFEN_MEMBERS", "MOVE_MEMBERS"]],
];

const permissionLabel = (name: PermissionName) =>
  name.charAt(0) + name.slice(1).toLowerCase().replaceAll("_", " ");

const roleHex = (color: number) => `#${color.toString(16).padStart(6, "0")}`;

// ── Overview ────────────────────────────────────────────
function OverviewTab({ server, onClosed }: { server: Server; onClosed: () => void }) {
  const selfId = useAuthStore((s) => s.user?.id);
  const client = useQueryClient();
  const navigate = useNavigate();
  const { data: perms } = useMyPermissions(server.id);
  const canManage = perms !== undefined && hasPermission(perms, Permissions.MANAGE_SERVER);
  const isOwner = server.ownerId === selfId;

  const [name, setName] = useState(server.name);
  const [iconUrl, setIconUrl] = useState(server.iconUrl ?? "");
  // Blob url of a just-uploaded icon, rendered until the form is saved.
  const [iconPreview, setIconPreview] = useState("");
  const [description, setDescription] = useState(server.description ?? "");
  const [deleteOpen, setDeleteOpen] = useState(false);

  const saveMutation = useMutation({
    mutationFn: () =>
      updateServer(server.id, {
        name: name.trim(),
        iconUrl: iconUrl.trim() ? iconUrl.trim() : null,
        description: description.trim() ? description.trim() : null,
      }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: serverKeys.detail(server.id) });
      client.invalidateQueries({ queryKey: serverKeys.list });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteServer(server.id),
    onSuccess: () => {
      onClosed();
      navigate("/");
    },
  });

  return (
    <div className="space-y-4">
      <TextField
        label="Server name"
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={100}
        disabled={!canManage}
      />
      <ImageField
        label="Server icon"
        kind="avatar"
        rounded="md"
        value={iconUrl}
        preview={iconPreview}
        onChange={(url, preview) => {
          setIconUrl(url);
          setIconPreview(preview);
        }}
        hint="Leave empty for initials."
        disabled={!canManage}
      />
      <label className="block">
        <span className="mb-1.5 block text-sm font-medium text-ink-secondary">Description</span>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          maxLength={1024}
          rows={3}
          disabled={!canManage}
          placeholder="What is this server about?"
          className="w-full resize-y rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm text-ink placeholder:text-ink-muted hover:border-border-strong disabled:opacity-60"
        />
        <span className="mt-1 block text-xs text-ink-muted">
          Shown on the invite page. {description.length}/1024
        </span>
      </label>
      {saveMutation.isError && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {saveMutation.error.message}
        </p>
      )}
      {canManage && (
        <Button
          loading={saveMutation.isPending}
          disabled={!name.trim()}
          onClick={() => saveMutation.mutate()}
        >
          Save changes
        </Button>
      )}

      {isOwner && (
        <div className="rounded-xl border border-danger/40 p-4">
          <p className="font-semibold text-danger">Danger zone</p>
          <p className="mt-1 text-sm text-ink-secondary">
            Deleting a server removes all its channels and messages. This cannot be undone.
          </p>
          <Button variant="danger" size="sm" className="mt-3" onClick={() => setDeleteOpen(true)}>
            Delete server
          </Button>
          <ConfirmDialog
            open={deleteOpen}
            onOpenChange={setDeleteOpen}
            title={`Delete ${server.name}?`}
            description="This permanently deletes the server, its channels, and every message."
            confirmLabel="Delete forever"
            danger
            loading={deleteMutation.isPending}
            error={deleteMutation.error?.message}
            onConfirm={() => deleteMutation.mutate()}
          />
        </div>
      )}
    </div>
  );
}

// ── Roles ───────────────────────────────────────────────
function RoleEditor({ server, role }: { server: Server; role: Role }) {
  const client = useQueryClient();
  const isEveryone = role.name === "@everyone";

  const [name, setName] = useState(role.name);
  const [color, setColor] = useState(roleHex(role.color));
  const [permissions, setPermissions] = useState(() => parsePermissions(role.permissions));
  const [deleteOpen, setDeleteOpen] = useState(false);

  const invalidate = () =>
    client.invalidateQueries({ queryKey: serverKeys.detail(server.id) });

  const saveMutation = useMutation({
    mutationFn: () =>
      updateRole(server.id, role.id, {
        ...(isEveryone ? {} : { name: name.trim(), color: parseInt(color.slice(1), 16) }),
        permissions: serializePermissions(permissions),
      }),
    onSuccess: invalidate,
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteRole(server.id, role.id),
    onSuccess: () => {
      invalidate();
      setDeleteOpen(false);
    },
  });

  const togglePermission = (bit: bigint, granted: boolean) =>
    setPermissions((prev) => (granted ? prev | bit : prev & ~bit));

  return (
    <div className="space-y-4">
      {!isEveryone && (
        <div className="flex items-end gap-3">
          <div className="flex-1">
            <TextField
              label="Role name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={100}
            />
          </div>
          <label className="flex flex-col gap-1.5 text-sm font-medium text-ink-secondary">
            Color
            <input
              type="color"
              value={color}
              onChange={(e) => setColor(e.target.value)}
              className="h-10 w-14 cursor-pointer rounded-lg border border-border bg-surface-1"
            />
          </label>
        </div>
      )}

      <div className="max-h-64 space-y-3 overflow-y-auto rounded-xl border border-border p-3">
        {PERMISSION_GROUPS.map(([group, names]) => (
          <fieldset key={group}>
            <legend className="pb-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">
              {group}
            </legend>
            <div className="grid grid-cols-2 gap-x-3 gap-y-1">
              {names.map((permName) => {
                const bit = Permissions[permName];
                return (
                  <label
                    key={permName}
                    className="flex cursor-pointer items-center gap-2 text-sm"
                  >
                    <input
                      type="checkbox"
                      checked={(permissions & bit) === bit}
                      onChange={(e) => togglePermission(bit, e.target.checked)}
                      className="accent-(--oc-primary)"
                    />
                    {permissionLabel(permName)}
                  </label>
                );
              })}
            </div>
          </fieldset>
        ))}
      </div>

      {(saveMutation.isError || deleteMutation.isError) && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {saveMutation.error?.message ?? deleteMutation.error?.message}
        </p>
      )}

      <div className="flex items-center gap-2">
        <Button size="sm" loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>
          Save role
        </Button>
        {!isEveryone && (
          <Button variant="ghost" size="sm" className="text-danger hover:text-danger" onClick={() => setDeleteOpen(true)}>
            <Trash2 aria-hidden className="size-4" />
            Delete
          </Button>
        )}
      </div>

      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title={`Delete ${role.name}?`}
        description="Members lose this role immediately."
        confirmLabel="Delete role"
        danger
        loading={deleteMutation.isPending}
        onConfirm={() => deleteMutation.mutate()}
      />
    </div>
  );
}

function RolesTab({ server, roles }: { server: Server; roles: Role[] }) {
  const client = useQueryClient();
  const sorted = useMemo(() => [...roles].sort((a, b) => b.position - a.position), [roles]);
  const [selectedId, setSelectedId] = useState<string | null>(sorted[0]?.id ?? null);
  const selected = sorted.find((r) => r.id === selectedId) ?? sorted[0];

  const createMutation = useMutation({
    mutationFn: () => createRole(server.id, { name: "new role" }),
    onSuccess: (role) => {
      client.invalidateQueries({ queryKey: serverKeys.detail(server.id) });
      setSelectedId(role.id);
    },
  });

  return (
    <div className="flex gap-4">
      <div className="w-40 shrink-0 space-y-0.5">
        <Button
          variant="secondary"
          size="sm"
          className="mb-2 w-full"
          loading={createMutation.isPending}
          onClick={() => createMutation.mutate()}
        >
          <Plus aria-hidden className="size-4" />
          New role
        </Button>
        {sorted.map((role) => (
          <button
            key={role.id}
            type="button"
            onClick={() => setSelectedId(role.id)}
            className={cn(
              "flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm transition-colors",
              selected?.id === role.id ? "bg-surface-3" : "hover:bg-surface-2",
            )}
          >
            <span
              className="size-2.5 shrink-0 rounded-full"
              style={{
                backgroundColor: role.color !== 0 ? roleHex(role.color) : "var(--oc-ink-muted)",
              }}
            />
            <span className="truncate">{role.name}</span>
          </button>
        ))}
      </div>
      <div className="min-w-0 flex-1">
        {selected ? (
          <RoleEditor key={selected.id} server={server} role={selected} />
        ) : (
          <p className="text-sm text-ink-muted">No roles yet.</p>
        )}
      </div>
    </div>
  );
}

// ── Bans ────────────────────────────────────────────────
function BansTab({ server }: { server: Server }) {
  const client = useQueryClient();
  const bansQuery = useQuery({
    queryKey: ["bans", server.id],
    queryFn: () => listBans(server.id),
  });

  const unbanMutation = useMutation({
    mutationFn: (userId: string) => unbanMember(server.id, userId),
    onSuccess: () => client.invalidateQueries({ queryKey: ["bans", server.id] }),
  });

  if (bansQuery.isLoading) {
    return <p className="py-6 text-center text-sm text-ink-muted">Loading bans…</p>;
  }
  if (bansQuery.isError) {
    return (
      <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
        {bansQuery.error.message}
      </p>
    );
  }

  const bans = bansQuery.data ?? [];
  if (bans.length === 0) {
    return <p className="py-6 text-center text-sm text-ink-muted">No banned users.</p>;
  }

  return (
    <ul className="max-h-72 space-y-1 overflow-y-auto">
      {bans.map((ban) => (
        <li key={ban.user.id} className="flex items-center gap-2.5 rounded-lg px-2 py-1.5">
          <Avatar user={ban.user} className="size-8" />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-medium">{ban.user.displayName}</span>
            {ban.reason && (
              <span className="block truncate text-xs text-ink-muted">{ban.reason}</span>
            )}
          </span>
          <Button
            variant="secondary"
            size="sm"
            loading={unbanMutation.isPending && unbanMutation.variables === ban.user.id}
            onClick={() => unbanMutation.mutate(ban.user.id)}
          >
            Unban
          </Button>
        </li>
      ))}
    </ul>
  );
}

/** Server settings: overview, role management, ban list - permission-gated tabs. */
export function ServerSettingsDialog({
  server,
  roles,
  open,
  onOpenChange,
}: ServerSettingsDialogProps) {
  const { data: perms } = useMyPermissions(server.id);
  const can = (p: bigint) => perms !== undefined && hasPermission(perms, p);
  const canRoles = can(Permissions.MANAGE_ROLES);
  const canBans = can(Permissions.BAN_MEMBERS);
  const canExpressions = can(Permissions.MANAGE_EXPRESSIONS);
  const canAudit = can(Permissions.VIEW_AUDIT_LOG);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Server settings" className="max-w-2xl">
        <Tabs defaultValue="overview">
          <TabsList>
            <TabsTrigger value="overview">Overview</TabsTrigger>
            {canRoles && <TabsTrigger value="roles">Roles</TabsTrigger>}
            {canExpressions && <TabsTrigger value="emojis">Emoji</TabsTrigger>}
            {canExpressions && <TabsTrigger value="sounds">Sounds</TabsTrigger>}
            {canBans && <TabsTrigger value="bans">Bans</TabsTrigger>}
            {canAudit && <TabsTrigger value="audit">Audit log</TabsTrigger>}
          </TabsList>
          <TabsContent value="overview" className="pt-4">
            <OverviewTab server={server} onClosed={() => onOpenChange(false)} />
          </TabsContent>
          {canRoles && (
            <TabsContent value="roles" className="pt-4">
              <RolesTab server={server} roles={roles} />
            </TabsContent>
          )}
          {canExpressions && (
            <TabsContent value="emojis" className="pt-4">
              <EmojiTab server={server} />
            </TabsContent>
          )}
          {canExpressions && (
            <TabsContent value="sounds" className="pt-4">
              <SoundTab server={server} />
            </TabsContent>
          )}
          {canBans && (
            <TabsContent value="bans" className="pt-4">
              <BansTab server={server} />
            </TabsContent>
          )}
          {canAudit && (
            <TabsContent value="audit" className="pt-4">
              <AuditLogTab server={server} />
            </TabsContent>
          )}
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}
