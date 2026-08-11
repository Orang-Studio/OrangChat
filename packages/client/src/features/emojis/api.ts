import type { Emoji } from "@orangchat/shared";
import { api } from "../../lib/api";
import { useAuthStore } from "../../stores/auth";
import { refreshSession } from "../auth/session";


export const MAX_EMOJI_BYTES = 256 * 1024;

export const listUsableEmojis = () => api<Emoji[]>("/emojis");

export const listServerEmojis = (serverId: string) =>
  api<Emoji[]>(`/servers/${serverId}/emojis`);

export const renameEmoji = (serverId: string, emojiId: string, name: string) =>
  api<Emoji>(`/servers/${serverId}/emojis/${emojiId}`, {
    method: "PATCH",
    json: { name },
  });

export const deleteEmoji = (serverId: string, emojiId: string) =>
  api<void>(`/servers/${serverId}/emojis/${emojiId}`, { method: "DELETE" });

/**
 * Upload one emoji. Raw fetch (multipart) with one 401-refresh retry, matching
 * `uploads/api.ts` - the shared `api()` helper is JSON-only.
 */
export async function uploadEmoji(
  serverId: string,
  file: File,
  name: string,
): Promise<Emoji> {
  const send = async (): Promise<Response> => {
    const token = useAuthStore.getState().accessToken;
    const form = new FormData();
    form.append("file", file);
    form.append("name", name);
    return fetch(`/api/servers/${serverId}/emojis`, {
      method: "POST",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: form,
      credentials: "include",
    });
  };

  let res = await send();
  if (res.status === 401 && (await refreshSession())) {
    res = await send();
  }
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as {
      error?: string;
      message?: string;
    };
    throw new Error(body.error ?? body.message ?? "Upload failed");
  }
  return (await res.json()) as Emoji;
}
