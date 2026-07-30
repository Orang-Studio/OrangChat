import { api } from "../../lib/api";

export interface Bot {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  createdAt: string;
  bot: true;
}

export interface BotToken {
  id: string;
  /** Last four characters, so two tokens can be told apart without revealing either. */
  hint: string;
  createdAt: string;
  lastUsedAt: string | null;
}

/** Only ever returned at mint. The server keeps a digest and cannot show it again. */
export interface MintedToken extends BotToken {
  token: string;
}

export const listBots = () => api<{ bots: Bot[] }>("/me/bots");

export const createBot = (username: string, displayName: string) =>
  api<{ bot: Bot; token: MintedToken }>("/me/bots", {
    method: "POST",
    json: { username, displayName },
  });

export const updateBot = (id: string, patch: { displayName?: string; avatarUrl?: string | null }) =>
  api<Bot>(`/me/bots/${id}`, { method: "PATCH", json: patch });

export const deleteBot = (id: string) =>
  api<{ ok: true }>(`/me/bots/${id}`, { method: "DELETE" });

export const listBotTokens = (id: string) =>
  api<{ tokens: BotToken[] }>(`/me/bots/${id}/tokens`);

export const mintBotToken = (id: string) =>
  api<MintedToken>(`/me/bots/${id}/tokens`, { method: "POST" });

export const revokeBotToken = (botId: string, tokenId: string) =>
  api<{ ok: true }>(`/me/bots/${botId}/tokens/${tokenId}`, { method: "DELETE" });

export const addBotToServer = (serverId: string, botId: string, permissions: string) =>
  api<{ ok: true }>(`/servers/${serverId}/bots`, {
    method: "POST",
    json: { botId, permissions },
  });
