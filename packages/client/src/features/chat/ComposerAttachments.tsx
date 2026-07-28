import { Eye, EyeOff, FileWarning, Paperclip, X } from "lucide-react";
import type { Attachment, SealedAttachmentRef } from "@orangchat/shared";
import { formatBytes } from "./attachments";

/** A file the user picked, from selection through to a finished upload. */
export interface PendingUpload {
  key: string;
  name: string;
  size: number;
  /** Headed for OrangMove, so it will expire an hour after it's sent. */
  ephemeral: boolean;
  /** 0–1 while uploading. */
  progress: number;
  /** Send it behind a spoiler cover. Chosen any time before send, not at pick. */
  spoiler: boolean;
  /** Set once the upload finishes; its id is what message:send references. */
  attachment?: Attachment;
  /** Opaque support blobs that must be claimed with the visible attachment. */
  supportingAttachments?: Attachment[];
  /**
   * Set instead of a readable descriptor in an encrypted conversation: the key
   * and the real filename go inside the message, never beside the upload.
   */
  sealed?: SealedAttachmentRef;
  error?: string;
  /** Local object URL for image previews; revoked when the entry goes away. */
  preview?: string;
  abort: () => void;
}

export const isSettled = (u: PendingUpload) => u.attachment !== undefined || u.error !== undefined;

function Chip({
  upload,
  onRemove,
  onToggleSpoiler,
}: {
  upload: PendingUpload;
  onRemove: () => void;
  onToggleSpoiler: () => void;
}) {
  const uploading = !isSettled(upload);
  const percent = Math.round(upload.progress * 100);

  return (
    <li
      className={`relative flex w-44 shrink-0 flex-col gap-1 overflow-hidden rounded-lg border bg-surface-4 p-2 ${
        upload.error ? "border-danger" : "border-border"
      }`}
    >
      <div className="flex items-start gap-2">
        {upload.preview ? (
          <img
            src={upload.preview}
            alt=""
            className={`size-9 shrink-0 rounded object-cover ${upload.spoiler ? "blur-[3px]" : ""}`}
          />
        ) : (
          <span className="grid size-9 shrink-0 place-items-center rounded bg-surface-2 text-ink-muted">
            <Paperclip aria-hidden className="size-4" />
          </span>
        )}
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs font-medium text-ink" title={upload.name}>
            {upload.name}
          </p>
          <p className="text-[11px] text-ink-muted">{formatBytes(upload.size)}</p>
        </div>
        <div className="flex shrink-0 items-center gap-0.5">
          <button
            type="button"
            onClick={onToggleSpoiler}
            aria-pressed={upload.spoiler}
            aria-label={
              upload.spoiler ? `Don't mark ${upload.name} as a spoiler` : `Mark ${upload.name} as a spoiler`
            }
            title={upload.spoiler ? "Marked as spoiler" : "Mark as spoiler"}
            className={`rounded p-0.5 transition-colors ${
              upload.spoiler ? "text-primary" : "text-ink-muted hover:text-ink"
            }`}
          >
            {upload.spoiler ? (
              <EyeOff aria-hidden className="size-3.5" />
            ) : (
              <Eye aria-hidden className="size-3.5" />
            )}
          </button>
          <button
            type="button"
            onClick={onRemove}
            aria-label={uploading ? `Cancel upload of ${upload.name}` : `Remove ${upload.name}`}
            className="rounded p-0.5 text-ink-muted transition-colors hover:text-ink"
          >
            <X aria-hidden className="size-3.5" />
          </button>
        </div>
      </div>

      {uploading && (
        <div
          className="h-1 overflow-hidden rounded-full bg-surface-1"
          role="progressbar"
          aria-valuenow={percent}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`Uploading ${upload.name}`}
        >
          <div
            className="h-full bg-primary transition-[width] duration-150"
            style={{ width: `${percent}%` }}
          />
        </div>
      )}

      {upload.error && (
        <p className="text-[11px] leading-tight text-danger" title={upload.error}>
          {upload.error}
        </p>
      )}

      {/* Say this up front rather than letting the file quietly rot later. */}
      {!upload.error && upload.ephemeral && (
        <p className="flex items-center gap-1 text-[11px] leading-tight text-warning">
          <FileWarning aria-hidden className="size-3 shrink-0" />
          Expires in 1 hour
        </p>
      )}
    </li>
  );
}

export function ComposerAttachments({
  uploads,
  onRemove,
  onToggleSpoiler,
}: {
  uploads: PendingUpload[];
  onRemove: (key: string) => void;
  onToggleSpoiler: (key: string) => void;
}) {
  if (uploads.length === 0) return null;
  return (
    <ul className="flex gap-2 overflow-x-auto border border-b-0 border-border bg-surface-1 px-3 py-2">
      {uploads.map((u) => (
        <Chip
          key={u.key}
          upload={u}
          onRemove={() => onRemove(u.key)}
          onToggleSpoiler={() => onToggleSpoiler(u.key)}
        />
      ))}
    </ul>
  );
}
