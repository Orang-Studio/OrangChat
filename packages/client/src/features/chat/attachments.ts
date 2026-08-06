import type { Attachment, SealedAttachmentRef } from '@orangchat/shared';
import { useAuthStore } from '../../stores/auth';
import { refreshSession } from '../auth/session';
import { imageSize, makePreview, sealForUpload, type LocalPreview } from '../e2ee/attachments';

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

/** Media metadata captured on the sender's device before the bytes go up. */
export interface MediaMeta {
  /** Seconds, for audio and video. */
  duration?: number;
  /** A still of the video's first frame, made here because the server cannot
   *  decode the encrypted bytes it stores. */
  thumbnail?: Blob;
}

const VIDEO_EXTENSION = /\.(mp4|m4v|webm|mkv|mov|3gp)$/i;
const AUDIO_EXTENSION = /\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)$/i;

function mediaContentType(file: File, isVideo: boolean, isAudio: boolean): string {
  if (file.type && file.type !== 'application/octet-stream') return file.type;
  const extension = file.name.split('.').pop()?.toLowerCase();
  if (isVideo) {
    return (
      (
        {
          mp4: 'video/mp4',
          m4v: 'video/x-m4v',
          webm: 'video/webm',
          mkv: 'video/x-matroska',
          mov: 'video/quicktime',
          '3gp': 'video/3gpp',
        } as Record<string, string>
      )[extension ?? ''] ?? 'video/mp4'
    );
  }
  if (isAudio) {
    return (
      (
        {
          mp3: 'audio/mpeg',
          m4a: 'audio/mp4',
          aac: 'audio/aac',
          ogg: 'audio/ogg',
          oga: 'audio/ogg',
          opus: 'audio/ogg',
          wav: 'audio/wav',
          flac: 'audio/flac',
        } as Record<string, string>
      )[extension ?? ''] ?? 'audio/mpeg'
    );
  }
  return file.type || 'application/octet-stream';
}

/** How long to wait for a local file's metadata before giving up on it. A
 *  probe is a courtesy preview, never something the send should hang on. */
const PROBE_TIMEOUT_MS = 10_000;
/** Longest thumbnail edge, matching the E2EE preview cap. */
const THUMB_EDGE = 400;

/**
 * Wait for a local media element's headers. Resolves false on error, and after
 * [PROBE_TIMEOUT_MS] no matter what - a probe is a courtesy, never something
 * the send hangs on.
 */
function awaitMetadata(element: HTMLMediaElement): Promise<boolean> {
  return new Promise((resolve) => {
    const timer = setTimeout(finish, PROBE_TIMEOUT_MS, true);
    function finish(ok: boolean) {
      clearTimeout(timer);
      element.onloadedmetadata = null;
      element.onerror = null;
      resolve(ok);
    }
    element.onloadedmetadata = () => finish(true);
    element.onerror = () => finish(false);
  });
}

/**
 * The duration of a local audio file, read from its headers. One pass over the
 * headers only - no bytes are played back.
 */
async function probeAudio(file: File): Promise<MediaMeta | null> {
  const url = URL.createObjectURL(file);
  try {
    const audio = document.createElement('audio');
    audio.preload = 'metadata';
    audio.src = url;
    if (!(await awaitMetadata(audio))) return null;
    const duration = audio.duration;
    return Number.isFinite(duration) && duration > 0 ? { duration } : {};
  } finally {
    URL.revokeObjectURL(url);
  }
}

function seekTo(video: HTMLVideoElement, time: number): Promise<void> {
  return new Promise((resolve) => {
    if (
      video.seekable.length > 0 &&
      time >= video.seekable.start(0) &&
      time <= video.seekable.end(video.seekable.length - 1)
    ) {
      video.onseeked = () => {
        video.onseeked = null;
        resolve();
      };
      video.currentTime = time;
    } else {
      resolve();
    }
  });
}

/**
 * Duration and a first-frame still for a local video. The first frame is not
 * always paintable - some encoders start with black frames - so the still is
 * taken just past the very start, which is still "the first frame" to anyone
 * looking at it.
 */
async function probeVideo(file: File): Promise<MediaMeta | null> {
  const url = URL.createObjectURL(file);
  try {
    const video = document.createElement('video');
    video.preload = 'metadata';
    video.src = url;
    if (!(await awaitMetadata(video))) return null;
    const duration =
      Number.isFinite(video.duration) && video.duration > 0 ? video.duration : undefined;

    let thumbnail: Blob | undefined;
    try {
      await seekTo(video, Math.min(0.5, duration ?? 0.5));
      thumbnail = (await captureFrame(video))?.blob;
    } catch {
      // A frame is a courtesy; the upload goes on without it.
    }
    return { ...(duration !== undefined ? { duration } : {}), ...(thumbnail ? { thumbnail } : {}) };
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** A painted frame and its dimensions, at most [THUMB_EDGE] on the long edge. */
async function captureFrame(video: HTMLVideoElement): Promise<{
  blob: Blob;
  width: number;
  height: number;
} | null> {
  if (video.videoWidth <= 0 || video.videoHeight <= 0) return null;
  const scale = Math.min(1, THUMB_EDGE / Math.max(video.videoWidth, video.videoHeight));
  const width = Math.max(1, Math.round(video.videoWidth * scale));
  const height = Math.max(1, Math.round(video.videoHeight * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) return null;
  context.drawImage(video, 0, 0, width, height);
  const blob = await new Promise<Blob | null>((resolve) =>
    canvas.toBlob(resolve, 'image/webp', 0.8),
  );
  return blob ? { blob, width, height } : null;
}

/**
 * A sealed video's preview: the duration and a first-frame still, both made on
 * this device because the server can never decode the sealed bytes. The still
 * is returned ready to be sealed as its own blob (see makePreview), so an
 * encrypted video gets a poster exactly like an encrypted image does.
 */
export async function makeVideoPreview(file: File): Promise<{
  duration?: number;
  preview: LocalPreview | null;
}> {
  const url = URL.createObjectURL(file);
  try {
    const video = document.createElement('video');
    video.preload = 'metadata';
    video.src = url;
    if (!(await awaitMetadata(video))) return { preview: null };
    const duration =
      Number.isFinite(video.duration) && video.duration > 0 ? video.duration : undefined;

    let preview: LocalPreview | null = null;
    try {
      await seekTo(video, Math.min(0.5, duration ?? 0.5));
      const frame = await captureFrame(video);
      if (frame) {
        preview = {
          bytes: new Uint8Array(await frame.blob.arrayBuffer()),
          contentType: frame.blob.type || 'image/webp',
          width: video.videoWidth,
          height: video.videoHeight,
        };
      }
    } catch {
      // A frame is a courtesy; the duration still rides along.
    }
    return { ...(duration !== undefined ? { duration } : {}), preview };
  } finally {
    URL.revokeObjectURL(url);
  }
}

/**
 * What to capture from a file before it is uploaded. Audio and video probe
 * their headers for a length; video also keeps a still of its first frame.
 * Anything that fails costs the preview, not the send.
 */
async function probeMedia(file: File): Promise<MediaMeta> {
  if (file.type.startsWith('video/') || VIDEO_EXTENSION.test(file.name)) {
    return (await probeVideo(file)) ?? {};
  }
  if (file.type.startsWith('audio/') || AUDIO_EXTENSION.test(file.name)) {
    return (await probeAudio(file)) ?? {};
  }
  return {};
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
    req.open('POST', url);
    req.withCredentials = true;
    if (token) req.setRequestHeader('Authorization', `Bearer ${token}`);

    if (onProgress) {
      req.upload.addEventListener('progress', (e) => {
        if (e.lengthComputable) onProgress(e.loaded / e.total);
      });
    }
    req.addEventListener('load', () => resolve({ status: req.status, body: req.responseText }));
    req.addEventListener('error', () => reject(new Error('Upload failed - check your connection')));
    req.addEventListener('abort', () => reject(new DOMException('Upload cancelled', 'AbortError')));

    if (signal) {
      if (signal.aborted) return reject(new DOMException('Upload cancelled', 'AbortError'));
      signal.addEventListener('abort', () => req.abort(), { once: true });
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

async function uploadLocal(
  file: File,
  handle: UploadHandle,
  sealed = false,
  meta: MediaMeta = {},
): Promise<Attachment> {
  const send = () => {
    const form = new FormData();
    form.append('file', file);
    if (meta.duration != null) form.append('duration', String(meta.duration));
    if (meta.thumbnail) form.append('thumbnail', meta.thumbnail, 'thumb.webp');
    return post(`/api/uploads/attachment${sealed ? '?sealed=1' : ''}`, form, {
      token: useAuthStore.getState().accessToken,
      ...handle,
    });
  };

  let res = await send();
  if (res.status === 401 && (await refreshSession())) res = await send();
  if (res.status < 200 || res.status >= 300) throw errorFrom(res, 'Upload failed');
  return JSON.parse(res.body) as Attachment;
}

async function uploadToOrangMove(
  file: File,
  handle: UploadHandle,
  meta: MediaMeta = {},
): Promise<Attachment> {
  const form = new FormData();
  form.append('ttl', String(ORANGMOVE_TTL_SECONDS));
  // Order matters: OrangMove streams fields as they arrive and reads `ttl` when
  // it validates, so it has to precede the file.
  form.append('file', file);

  const res = await post('/orangmove/upload', form, handle);
  if (res.status < 200 || res.status >= 300) {
    // OrangMove takes any file type, so a rejection here is about size, a full
    // store or a bad TTL rather than what the file is.
    throw errorFrom(res, 'The file service could not accept this file');
  }
  const { token } = JSON.parse(res.body) as { token: string };

  // The thumbnail is small by construction, so it goes through the direct
  // route and is handed to the registration as a row to claim.
  let thumbnailId: string | undefined;
  if (meta.thumbnail) {
    try {
      const thumb = new File([meta.thumbnail], 'thumb.webp', {
        type: meta.thumbnail.type || 'image/webp',
      });
      thumbnailId = (await uploadLocal(thumb, {})).id;
    } catch {
      // A preview that failed to upload costs a thumbnail, not the file.
    }
  }

  // Registering is what makes it an attachment: OrangChat re-reads the file's
  // real name and size from OrangMove rather than trusting anything from here.
  const register = async (): Promise<RawResponse> => {
    const accessToken = useAuthStore.getState().accessToken;
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
    const response = await fetch('/api/uploads/attachment/external', {
      method: 'POST',
      headers,
      credentials: 'include',
      body: JSON.stringify({
        token,
        ...(meta.duration != null ? { duration: meta.duration } : {}),
        ...(thumbnailId ? { thumbnailId } : {}),
      }),
    });
    return { status: response.status, body: await response.text() };
  };

  let reg = await register();
  if (reg.status === 401 && (await refreshSession())) reg = await register();
  if (reg.status < 200 || reg.status >= 300) throw errorFrom(reg, 'Could not attach the file');
  return JSON.parse(reg.body) as Attachment;
}

/** Upload one file and return the attachment to reference in message:send. */
export async function uploadAttachment(file: File, handle: UploadHandle = {}): Promise<Attachment> {
  if (file.size === 0) throw new Error(`"${file.name}" is empty`);
  if (file.size > MAX_ATTACHMENT) {
    throw new Error(`"${file.name}" is over the 1GB limit`);
  }
  const meta = await probeMedia(file);
  return file.size > MAX_LOCAL_ATTACHMENT
    ? uploadToOrangMove(file, handle, meta)
    : uploadLocal(file, handle, false, meta);
}

export interface SealedAttachmentUpload {
  /** The row the server wrote; its filename and type are placeholders. */
  attachment: Attachment;
  /** Supporting opaque rows (the encrypted media thumbnail, when available). */
  supportingAttachments: Attachment[];
  /** What the message payload has to carry for anyone to read the file. */
  ref: SealedAttachmentRef;
}

async function uploadSealedBlob(
  bytes: Uint8Array,
  handle: UploadHandle,
): Promise<{ attachment: Attachment; fileId: string; key: string; nonce: string }> {
  const sealed = await sealForUpload(bytes);
  const attachment =
    sealed.file.size > MAX_LOCAL_ATTACHMENT
      ? await uploadToOrangMove(sealed.file, handle)
      : await uploadLocal(sealed.file, handle, true);
  return { attachment, fileId: sealed.fileId, key: sealed.key, nonce: sealed.nonce };
}

/**
 * Uploads a file into an end-to-end encrypted conversation. Everything the
 * server would normally learn from an upload - the name, the type, the pixels -
 * is sealed here first, so the row it writes describes a blob and a length.
 *
 * Images additionally get a locally generated preview, sealed as its own blob
 * under its own key: Cloudinary cannot transform bytes it cannot read, so a
 * client-made thumbnail is the only preview an encrypted image will ever have.
 */
export async function uploadSealedAttachment(
  file: File,
  handle: UploadHandle = {},
): Promise<SealedAttachmentUpload> {
  if (file.size === 0) throw new Error(`"${file.name}" is empty`);
  if (file.size > MAX_ATTACHMENT) throw new Error(`"${file.name}" is over the 1GB limit`);

  const preview = await makePreview(file);
  const isVideo = file.type.startsWith('video/') || VIDEO_EXTENSION.test(file.name);
  const isAudio = file.type.startsWith('audio/') || AUDIO_EXTENSION.test(file.name);
  const contentType = mediaContentType(file, isVideo, isAudio);
  const video = isVideo ? await makeVideoPreview(file) : undefined;
  const audio = !isVideo && isAudio ? await probeAudio(file) : undefined;
  const dims = preview ?? video?.preview ?? (await imageSize(file));

  const main = await uploadSealedBlob(new Uint8Array(await file.arrayBuffer()), handle);

  let thumb: SealedAttachmentRef['thumb'];
  const supportingAttachments: Attachment[] = [];
  const thumbSource = preview ?? video?.preview;
  if (thumbSource) {
    try {
      const uploaded = await uploadSealedBlob(thumbSource.bytes, {});
      supportingAttachments.push(uploaded.attachment);
      thumb = {
        fileId: uploaded.fileId,
        attachmentId: uploaded.attachment.id,
        key: uploaded.key,
        nonce: uploaded.nonce,
        contentType: thumbSource.contentType,
        size: thumbSource.bytes.length,
      };
    } catch {
      // A preview that failed to upload costs a thumbnail, not the message.
    }
  }

  return {
    attachment: main.attachment,
    supportingAttachments,
    ref: {
      fileId: main.fileId,
      attachmentId: main.attachment.id,
      key: main.key,
      nonce: main.nonce,
      filename: file.name,
      contentType,
      size: file.size,
      ...((video?.duration ?? audio?.duration) !== undefined
        ? { duration: video?.duration ?? audio?.duration }
        : {}),
      ...(dims ? { width: dims.width, height: dims.height } : {}),
      ...(thumb ? { thumb } : {}),
    },
  };
}

/** True for files that will be stored on OrangMove, and so will expire. */
export const isEphemeral = (file: File) => file.size > MAX_LOCAL_ATTACHMENT;

/**
 * The url to point an `<img>`, `<video>` or `<audio>` at.
 *
 * OrangMove serves `/file/` as `application/octet-stream` under
 * `X-Content-Type-Options: nosniff` and `Content-Disposition: attachment`,
 * which is exactly what a browser refuses to render: the declared type is not
 * an image or media type and nosniff forbids looking past it. That is why every
 * attachment over 10MB showed up as a broken preview. Its `/view/` route exists
 * for this - same bytes, real type detected from magic bytes, served inline
 * under `default-src 'none'; sandbox` so a mislabelled file still can't run.
 *
 * Downloads keep using `/file/`, which is the one that names the file.
 */
export function inlineUrl(attachment: Pick<Attachment, 'url'>): string {
  const token = attachment.url.startsWith('/orangmove/file/')
    ? attachment.url.slice('/orangmove/file/'.length)
    : null;
  return token ? `/orangmove/view/${token}` : attachment.url;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

/** Seconds as `m:ss`, for media lengths. */
export function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '0:00';
  const total = Math.floor(seconds);
  const mins = Math.floor(total / 60);
  return `${mins}:${String(total % 60).padStart(2, '0')}`;
}
