import type {
  Connection,
  ConnectionProvider,
  ConnectionProviderInfo,
} from "@orangchat/shared";
import { api } from "../../lib/api";

export const getMyConnections = () =>
  api<{ items: Connection[] }>("/connections").then((r) => r.items);

export const getUserConnections = (userId: string) =>
  api<{ items: Connection[] }>(`/users/${userId}/connections`).then((r) => r.items);

export const getConnectionProviders = () =>
  api<{ items: ConnectionProviderInfo[] }>("/connections/providers").then((r) => r.items);

/**
 * Ask the server for the consent URL and hand the tab over to the platform.
 * The redirect can't be a plain `<a href>` to the API: the browser wouldn't
 * send our Bearer token, so the server couldn't tell whose account to link.
 */
export async function startConnectionLink(provider: ConnectionProvider) {
  const { url } = await api<{ url: string }>(`/connections/${provider}/authorize`, {
    method: "POST",
  });
  window.location.href = url;
}

export const addCustomConnection = (name: string, url: string) =>
  api<Connection>("/connections/custom", { method: "POST", json: { name, url } });

export const setConnectionVisible = (id: string, visible: boolean) =>
  api<Connection>(`/connections/${id}`, { method: "PATCH", json: { visible } });

export const removeConnection = (id: string) =>
  api<{ ok: true }>(`/connections/${id}`, { method: "DELETE" });
