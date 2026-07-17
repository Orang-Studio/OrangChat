import type { Attachment } from "@orangchat/shared";
import { useAuthStore } from "../../stores/auth";
import { refreshSession } from "../auth/session";

/**
 * Where a file goes depends only on its size:
 *
 * * **≤ 10MB** - posted to OrangChat, which stores it (Cloudinary, or its own
 *   disk when that's unconfigured), kept as long as the message.
 * * **> 10MB** - posted straight to OrangMove (same-origin via the /orangmove
 *   proxy), then registered with OrangChat by token. OrangChat never sees the
 *   bytes, which is the point: a 1GB file would otherwise be uploaded twice.
 *
 * The catch is that OrangMove is an ephemeral store - an hour is the longest it
 * will keep anything - so large attachments carry an `expiresAt` and stop
 * resolving after it. That's a property of the store, not a bug to fix here;
 * the UI shows the deadline instead of pretending it isn't coming.
 */

/** Must match MAX_LOCAL_ATTACHMENT in server-rs/src/http/attachments.rs. */
// AES-GCM's version header, nonce and authentication tag consume 36 bytes of
// Cloudinary's 10 MiB raw-asset limit.
export const MAX_LOCAL_ATTACHMENT = 10 * 1024 * 1024 - 36;
/** OrangMove's hard ceiling (MAX_SIZE). Nothing bigger can be sent at all. */
export const MAX_ATTACHMENT = 1024 * 1024 * 1024;
/** Mirrors attachmentIds.max(10) in shared/schemas.ts. */
export const MAX_PER_MESSAGE = 10;
/** OrangMove's MAX_TTL. Asking for more is rejected, so this is as long as a large file can live. */
const ORANGMOVE_TTL_SECONDS = 3600;

export interface UploadHandle {
  onProgress?: (fraction: number) => void;
  signal?: AbortSignal;
}

interface RawResponse {
  status: number;
  body: string;
}

/**
 * fetch() can't report upload progress, and these go up to a gigabyte - a
 * progressless 1GB upload is indistinguishable from a hung one, so XHR it is.
 */
function post(
  url: string,
  form: FormData,
  { token, onProgress, signal }: { token?: string | null } & UploadHandle,
): Promise<RawResponse> {
  return new Promise((resolve, reject) => {
    const req = new XMLHttpRequest();
    req.open("POST", url);
    req.withCredentials = true;
    if (token) req.setRequestHeader("Authorization", `Bearer ${token}`);

    if (onProgress) {
      req.upload.addEventListener("progress", (e) => {
        if (e.lengthComputable) onProgress(e.loaded / e.total);
      });
    }
    req.addEventListener("load", () => resolve({ status: req.status, body: req.responseText }));
    req.addEventListener("error", () => reject(new Error("Upload failed - check your connection")));
    req.addEventListener("abort", () => reject(new DOMException("Upload cancelled", "AbortError")));

    if (signal) {
      if (signal.aborted) return reject(new DOMException("Upload cancelled", "AbortError"));
      signal.addEventListener("abort", () => req.abort(), { once: true });
    }
    req.send(form);
  });
}

/** OrangChat answers with `{error}`; OrangMove answers in plain text. */
function errorFrom(res: RawResponse, fallback: string): Error {
  try {
    const parsed = JSON.parse(res.body) as { error?: string; message?: string };
    if (parsed.error ?? parsed.message) return new Error(parsed.error ?? parsed.message!);
  } catch {
    // Not JSON - fall through to the raw text.
  }
  const text = res.body.trim();
  return new Error(text.length > 0 && text.length < 200 ? text : fallback);
}

async function uploadLocal(file: File, handle: UploadHandle): Promise<Attachment> {
  const send = () => {
    const form = new FormData();
    form.append("file", file);
    return post("/api/uploads/attachment", form, {
      token: useAuthStore.getState().accessToken,
      ...handle,
    });
  };

  let res = await send();
  if (res.status === 401 && (await refreshSession())) res = await send();
  if (res.status < 200 || res.status >= 300) throw errorFrom(res, "Upload failed");
  return JSON.parse(res.body) as Attachment;
}

async function uploadToOrangMove(file: File, handle: UploadHandle): Promise<Attachment> {
  const form = new FormData();
  form.append("ttl", String(ORANGMOVE_TTL_SECONDS));
  // Order matters: OrangMove streams fields as they arrive and reads `ttl` when
  // it validates, so it has to precede the file.
  form.append("file", file);

  const res = await post("/orangmove/upload", form, handle);
  if (res.status < 200 || res.status >= 300) {
    // OrangMove rejects executables by magic bytes and extension, so this is the
    // likeliest way a large upload fails.
    throw errorFrom(res, "The file service rejected this file");
  }
  const { token } = JSON.parse(res.body) as { token: string };

  // Registering is what makes it an attachment: OrangChat re-reads the file's
  // real name and size from OrangMove rather than trusting anything from here.
  const register = async (): Promise<RawResponse> => {
    const accessToken = useAuthStore.getState().accessToken;
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (accessToken) headers["Authorization"] = `Bearer ${accessToken}`;
    const response = await fetch("/api/uploads/attachment/external", {
      method: "POST",
      headers,
      credentials: "include",
      body: JSON.stringify({ token }),
    });
    return { status: response.status, body: await response.text() };
  };

  let reg = await register();
  if (reg.status === 401 && (await refreshSession())) reg = await register();
  if (reg.status < 200 || reg.status >= 300) throw errorFrom(reg, "Could not attach the file");
  return JSON.parse(reg.body) as Attachment;
}

/** Upload one file and return the attachment to reference in message:send. */
export async function uploadAttachment(file: File, handle: UploadHandle = {}): Promise<Attachment> {
  if (file.size === 0) throw new Error(`"${file.name}" is empty`);
  if (file.size > MAX_ATTACHMENT) {
    throw new Error(`"${file.name}" is over the 1GB limit`);
  }
  return file.size > MAX_LOCAL_ATTACHMENT
    ? uploadToOrangMove(file, handle)
    : uploadLocal(file, handle);
}

/** True for files that will be stored on OrangMove, and so will expire. */
export const isEphemeral = (file: File) => file.size > MAX_LOCAL_ATTACHMENT;

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}
