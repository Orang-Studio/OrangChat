import {
  fromBase64,
  openFile,
  sealFile,
  toBase64,
  type SealedAttachmentRef,
} from '@orangchat/shared';




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


export function isSealedThumbnail(attachmentId: string): boolean {
  const ref = refs.get(attachmentId);
  return ref?.thumb?.attachmentId === attachmentId;
}


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
  return new Blob([plaintext.slice().buffer], { type: ref.contentType });
}


export function sealedObjectUrl(ref: SealedBlobRef, url: string): Promise<string> {
  const existing = objectUrls.get(ref.attachmentId);
  if (existing) return existing;
  const created = fetchSealed(ref, url).then((blob) => URL.createObjectURL(blob));
  objectUrls.set(ref.attachmentId, created);
  created.catch(() => objectUrls.delete(ref.attachmentId));
  return created;
}


export const MAX_INLINE_SEALED = 64 * 1024 * 1024;

export interface SealedUpload {
  file: File;
  fileId: string;
  key: string;
  nonce: string;
}


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

  blur?: string;
}

const THUMB_EDGE = 400;


const BLUR_EDGE = 16;


const MAX_BLUR_BYTES = 1024;


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
