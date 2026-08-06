import type {
  AuditLogEntry,
  Channel,
  CreateChannelInput,
  CreateInviteInput,
  CreateServerInput,
  Invite,
  InvitePreview,
  Role,
  Server,
  ServerMember,
  UpdateServerInput,
} from "@orangchat/shared";
import { api } from "../../lib/api";

/** GET /servers/:id response - server plus everything the shell needs. */
export interface ServerDetail {
  server: Server;
  channels: Channel[];
  roles: Role[];
  members: ServerMember[];
}

export const listServers = () => api<Server[]>("/servers");

export const createServer = (input: CreateServerInput) =>
  api<Server>("/servers", { method: "POST", json: input });

export const getServerDetail = (serverId: string) =>
  api<ServerDetail>(`/servers/${serverId}`);

export const createChannel = (serverId: string, input: CreateChannelInput) =>
  api<Channel>(`/servers/${serverId}/channels`, { method: "POST", json: input });

export const createInvite = (serverId: string, input: CreateInviteInput = {}) =>
  api<Invite>(`/servers/${serverId}/invites`, { method: "POST", json: input });

export const joinViaInvite = (code: string) =>
  api<Server>(`/invites/${encodeURIComponent(code.trim())}`, { method: "POST" });

/** Resolve an invite link without joining. Works signed-out. */
export const getInvitePreview = (code: string, signal?: AbortSignal) =>
  api<InvitePreview>(`/invites/${encodeURIComponent(code.trim())}`, { signal });

export const getMyPermissions = (serverId: string) =>
  api<{ permissions: string }>(`/servers/${serverId}/me/permissions`);

export const updateServer = (serverId: string, input: UpdateServerInput) =>
  api<Server>(`/servers/${serverId}`, { method: "PATCH", json: input });

export const deleteServer = (serverId: string) =>
  api<void>(`/servers/${serverId}`, { method: "DELETE" });

/** Leave a server you're a member of. The owner has to delete it instead. */
export const leaveServer = (serverId: string) =>
  api<void>(`/servers/${serverId}/leave`, { method: "POST" });

/** GET /servers/:id/audit-log - newest first, offset-paginated. */
export interface AuditLogPage {
  items: AuditLogEntry[];
  nextCursor: string | null;
}

export const getAuditLog = (serverId: string, offset = 0, action?: string) => {
  const query = new URLSearchParams({ limit: "50", offset: String(offset) });
  if (action) query.set("action", action);
  return api<AuditLogPage>(`/servers/${serverId}/audit-log?${query.toString()}`);
};
