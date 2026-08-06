import {
  fromBase64,
  openFile,
  sealFile,
  toBase64,
  type SealedAttachmentRef,
} from '@orangchat/shared';

/**
 * Attachments in an encrypted conversation (docs/E2EE.md §7). The bytes, the
 * filename and the content type are all sealed on the client under a key the
 * server never sees; its row keeps a storage id and a byte length.
 *
 * Two consequences worth stating rather than discovering: Cloudinary
 * transformations and server-side thumbnails are dead here, so previews are
 * generated locally and sealed as a second blob; and `image_moderation` cannot
 * run, which is a product decision §7 makes explicitly.
 */

/**
 * Which sealed file each attachment row actually is. Populated as messages are
 * decrypted, because the key only ever exists inside the message payload.
 */
const refs = new Map<string, SealedAttachmentRef>();

type SealedBlobRef = Pick<
  SealedAttachmentRef,
  'fileId' | 'attachmentId' | 'key' | 'nonce' | 'contentType'
>;

export function rememberSealedAttachments(list: readonly SealedAttachmentRef[] | undefined): void {
  for (const ref of list ?? []) {
    refs.set(ref.attachmentId, ref);
    if (ref.thumb)
      refs.set(ref.thumb.attachmentId, { ...ref, ...ref.thumb, filename: ref.filename });
  }
}

export function sealedAttachmentsOf(attachmentId: string): SealedAttachmentRef | null {
  return refs.get(attachmentId) ?? null;
}

/** Supporting thumbnail rows are claimed by the message but not separate files. */
export function isSealedThumbnail(attachmentId: string): boolean {
  const ref = refs.get(attachmentId);
  return ref?.thumb?.attachmentId === attachmentId;
}

/** Object URLs are revoked with the page, so one per attachment is the cap. */
const objectUrls = new Map<string, Promise<string>>();

async function fetchSealed(ref: SealedBlobRef, url: string): Promise<Blob> {
  const response = await fetch(url, { credentials: 'include' });
  if (!response.ok) throw new Error('This attachment could not be downloaded.');
  const ciphertext = new Uint8Array(await response.arrayBuffer());
  const plaintext = await openFile(
    fromBase64(ref.key),
    fromBase64(ref.nonce),
    ref.fileId,
    ciphertext,
  );
  // Slice into a fresh buffer: `plaintext` is a view and Blob would otherwise
  // capture whatever else shares its backing store.
  return new Blob([plaintext.slice().buffer], { type: ref.contentType });
}

/**
 * Decrypts a sealed attachment and hands back a URL the normal media elements
 * can use, so nothing downstream has to know the difference.
 */
export function sealedObjectUrl(ref: SealedBlobRef, url: string): Promise<string> {
  const existing = objectUrls.get(ref.attachmentId);
  if (existing) return existing;
  const created = fetchSealed(ref, url).then((blob) => URL.createObjectURL(blob));
  objectUrls.set(ref.attachmentId, created);
  created.catch(() => objectUrls.delete(ref.attachmentId));
  return created;
}

/**
 * Above this, a sealed file is offered as a download rather than played inline.
 * Decryption is all-or-nothing - AES-GCM has one tag over the whole file - so
 * an inline player means the entire thing in memory, and a gigabyte of video
 * would take the tab with it.
 */
export const MAX_INLINE_SEALED = 64 * 1024 * 1024;

export interface SealedUpload {
  file: File;
  fileId: string;
  key: string;
  nonce: string;
}

/** Seals a file's bytes; its name and type travel in the message payload. */
export async function sealForUpload(bytes: Uint8Array): Promise<SealedUpload> {
  const sealed = await sealFile(bytes);
  return {
    file: new File([sealed.bytes.slice().buffer], 'sealed.ocf', {
      type: 'application/octet-stream',
    }),
    fileId: sealed.fileId,
    key: toBase64(sealed.key),
    nonce: toBase64(sealed.nonce),
  };
}

export interface LocalPreview {
  bytes: Uint8Array;
  contentType: string;
  width: number;
  height: number;
  /** base64 micro-JPEG for the payload itself. See `SealedAttachmentRef.blur`. */
  blur?: string;
}

const THUMB_EDGE = 400;

/**
 * Long edge of the inline blur, in pixels.
 *
 * The binding constraint is MAX_PUSH_CIPHERTEXT_CHARS in server-rs
 * (services/push.rs): a message whose ciphertext runs past 2600 characters is
 * pushed *without* its envelope, so an over-generous stamp would trade a black
 * rectangle for a notification that no longer says anything. The stamp costs
 * roughly 1.8 characters of ciphertext per byte - base64 into the payload, then
 * base64 again out of the seal - which leaves about a kilobyte to spend
 * alongside a message's text and its attachment keys.
 *
 * Sixteen pixels is still more detail than BlurHash carries, and blown up and
 * blurred it is indistinguishable from a bigger one.
 */
const BLUR_EDGE = 16;

/**
 * Hard ceiling, not a target: at [BLUR_EDGE] even pure noise encodes smaller
 * than this, so tripping it means something is wrong and the stamp is dropped.
 */
const MAX_BLUR_BYTES = 1024;

/**
 * The same frame again at postage-stamp size, base64, to travel in the message.
 *
 * Drawn from whatever the caller already has decoded, so it costs one more
 * canvas and no extra decode. Returns undefined rather than throwing: this is
 * the cheapest thing in the pipeline and the least worth failing a send over.
 */
export async function blurStamp(
  source: CanvasImageSource,
  width: number,
  height: number,
): Promise<string | undefined> {
  if (width <= 0 || height <= 0) return undefined;
  try {
    const scale = Math.min(1, BLUR_EDGE / Math.max(width, height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(width * scale));
    canvas.height = Math.max(1, Math.round(height * scale));
    const context = canvas.getContext('2d');
    if (!context) return undefined;
    context.drawImage(source, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', 0.45),
    );
    if (!blob || blob.size > MAX_BLUR_BYTES) return undefined;
    return toBase64(new Uint8Array(await blob.arrayBuffer()));
  } catch {
    return undefined;
  }
}

/** Render a stored blur as something an `<img>` can point at. */
export function blurDataUrl(blur: string | undefined): string | undefined {
  return blur ? `data:image/jpeg;base64,${blur}` : undefined;
}

/**
 * A preview the client makes for itself. The server cannot transform bytes it
 * cannot read, so this is the only way an encrypted image gets a cheap preview
 * and the only way its dimensions are known before it is fully decrypted.
 */
export async function makePreview(file: File): Promise<LocalPreview | null> {
  if (!file.type.startsWith('image/')) return null;
  if (typeof createImageBitmap !== 'function') return null;

  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    return null;
  }

  try {
    const scale = Math.min(1, THUMB_EDGE / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) return null;
    context.drawImage(bitmap, 0, 0, width, height);

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/webp', 0.8),
    );
    if (!blob) return null;
    return {
      bytes: new Uint8Array(await blob.arrayBuffer()),
      contentType: blob.type || 'image/webp',
      width: bitmap.width,
      height: bitmap.height,
      blur: await blurStamp(bitmap, bitmap.width, bitmap.height),
    };
  } finally {
    bitmap.close();
  }
}

/** Dimensions for an image that is too big to preview but still worth sizing. */
export async function imageSize(file: File): Promise<{ width: number; height: number } | null> {
  if (!file.type.startsWith('image/')) return null;
  if (typeof createImageBitmap !== 'function') return null;
  try {
    const bitmap = await createImageBitmap(file);
    const size = { width: bitmap.width, height: bitmap.height };
    bitmap.close();
    return size;
  } catch {
    return null;
  }
}
