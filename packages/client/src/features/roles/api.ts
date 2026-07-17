import type {
  CreateRoleInput,
  Role,
  ServerMember,
  UpdateRoleInput,
  User,
} from "@orangchat/shared";
import { api } from "../../lib/api";

export interface Ban {
  user: User;
  reason: string | null;
  bannedById: string;
  createdAt: string;
}

// ── Roles ─────────────────────────────────────────────
export const createRole = (serverId: string, input: CreateRoleInput) =>
  api<Role>(`/servers/${serverId}/roles`, { method: "POST", json: input });

export const updateRole = (serverId: string, roleId: string, input: UpdateRoleInput) =>
  api<Role>(`/servers/${serverId}/roles/${roleId}`, { method: "PATCH", json: input });

export const deleteRole = (serverId: string, roleId: string) =>
  api<void>(`/servers/${serverId}/roles/${roleId}`, { method: "DELETE" });

export const assignRole = (serverId: string, userId: string, roleId: string) =>
  api<ServerMember>(`/servers/${serverId}/members/${userId}/roles/${roleId}`, {
    method: "PUT",
  });

export const unassignRole = (serverId: string, userId: string, roleId: string) =>
  api<ServerMember>(`/servers/${serverId}/members/${userId}/roles/${roleId}`, {
    method: "DELETE",
  });

// ── Nicknames ─────────────────────────────────────────
export const setNickname = (
  serverId: string,
  userId: string | "@me",
  nickname: string | null,
) =>
  api<ServerMember>(`/servers/${serverId}/members/${userId}/nickname`, {
    method: "PATCH",
    json: { nickname },
  });

// ── Moderation ────────────────────────────────────────
export const kickMember = (serverId: string, userId: string) =>
  api<void>(`/servers/${serverId}/members/${userId}`, { method: "DELETE" });

export const banMember = (serverId: string, userId: string, reason?: string) =>
  api<void>(`/servers/${serverId}/bans/${userId}`, {
    method: "POST",
    json: reason ? { reason } : {},
  });

export const unbanMember = (serverId: string, userId: string) =>
  api<void>(`/servers/${serverId}/bans/${userId}`, { method: "DELETE" });

export const listBans = (serverId: string) => api<Ban[]>(`/servers/${serverId}/bans`);
