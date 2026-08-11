import { Eye, EyeOff, FileWarning, Paperclip, X } from "lucide-react";
import type { Attachment, SealedAttachmentRef } from "@orangchat/shared";
import { formatBytes } from "./attachments";
import { t } from "../../lib/i18n";


export interface PendingUpload {
  key: string;
  name: string;
  size: number;

  ephemeral: boolean;

  progress: number;

  spoiler: boolean;

  attachment?: Attachment;

  supportingAttachments?: Attachment[];

  sealed?: SealedAttachmentRef;
  error?: string;

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
            className={`rounded-lg p-2 transition-colors ${
              upload.spoiler ? "text-primary" : "text-ink-muted hover:text-ink"
            }`}
          >
            {upload.spoiler ? (
              <EyeOff aria-hidden className="size-4" />
            ) : (
              <Eye aria-hidden className="size-4" />
            )}
          </button>
          <button
            type="button"
            onClick={onRemove}
            aria-label={uploading ? `Cancel upload of ${upload.name}` : `Remove ${upload.name}`}
            className="rounded-lg p-2 text-ink-muted transition-colors hover:text-ink"
          >
            <X aria-hidden className="size-4" />
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
          {t("composerAttachments.expiresIn1Hour")}
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
