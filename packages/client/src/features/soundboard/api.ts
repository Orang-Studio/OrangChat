import type { Sound } from "@orangchat/shared";
import { api } from "../../lib/api";
import { useAuthStore } from "../../stores/auth";
import { refreshSession } from "../auth/session";

/** Matches the server's cap. */
export const MAX_SOUND_BYTES = 1024 * 1024;
/** Matches services::audio::MAX_SOUND_SECS. */
export const MAX_SOUND_SECS = 3;

export const listSounds = (serverId: string) => api<Sound[]>(`/servers/${serverId}/sounds`);

export const updateSound = (
  serverId: string,
  soundId: string,
  patch: { name?: string; emoji?: string | null; volume?: number },
) => api<Sound>(`/servers/${serverId}/sounds/${soundId}`, { method: "PATCH", json: patch });

export const deleteSound = (serverId: string, soundId: string) =>
  api<void>(`/servers/${serverId}/sounds/${soundId}`, { method: "DELETE" });

/** Upload one clip. Raw fetch (multipart); the shared `api()` helper is JSON-only. */
export async function uploadSound(
  serverId: string,
  file: File,
  name: string,
  emoji?: string,
): Promise<Sound> {
  const send = async (): Promise<Response> => {
    const token = useAuthStore.getState().accessToken;
    const form = new FormData();
    form.append("file", file);
    form.append("name", name);
    if (emoji) form.append("emoji", emoji);
    return fetch(`/api/servers/${serverId}/sounds`, {
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
  return (await res.json()) as Sound;
}
