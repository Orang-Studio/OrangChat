import * as RadixDialog from "@radix-ui/react-dialog";
import { Download, X } from "lucide-react";
import type { Attachment } from "@orangchat/shared";
import { formatBytes } from "./attachments";

/** Full-screen player for a video attachment, with downloading kept explicit. */
export function VideoLightbox({
  attachment,
  open,
  startTime,
  onOpenChange,
}: {
  attachment: Attachment;
  open: boolean;
  startTime: number;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/80" />
        <RadixDialog.Content
          aria-describedby={undefined}
          className="fixed inset-0 z-50 flex flex-col focus:outline-none"
          onCloseAutoFocus={(event) => event.preventDefault()}
        >
          <RadixDialog.Title className="sr-only">{attachment.filename}</RadixDialog.Title>

          <div className="flex shrink-0 items-center gap-3 bg-black/40 px-4 py-2.5 text-white">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{attachment.filename}</p>
              {attachment.size > 0 && (
                <p className="text-[11px] text-white/60">{formatBytes(attachment.size)}</p>
              )}
            </div>
            <a
              href={attachment.url}
              download={attachment.filename}
              aria-label={`Download ${attachment.filename}`}
              className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors hover:bg-white/15"
            >
              <Download aria-hidden className="size-4" />
              Download
            </a>
            <RadixDialog.Close
              aria-label="Close"
              className="rounded-lg p-1.5 transition-colors hover:bg-white/15"
            >
              <X aria-hidden className="size-4" />
            </RadixDialog.Close>
          </div>

          <div
            className="flex min-h-0 flex-1 items-center justify-center p-4"
            onClick={() => onOpenChange(false)}
          >
            <video
              src={attachment.url}
              aria-label={attachment.filename}
              controls
              controlsList="nodownload"
              autoPlay
              playsInline
              preload="metadata"
              onLoadedMetadata={(event) => {
                if (startTime > 0) event.currentTarget.currentTime = startTime;
              }}
              onClick={(event) => event.stopPropagation()}
              className="max-h-full max-w-full bg-black object-contain"
            />
          </div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
