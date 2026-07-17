import * as RadixDialog from "@radix-ui/react-dialog";
import { Download, X } from "lucide-react";
import type { Attachment } from "@orangchat/shared";
import { formatBytes } from "./attachments";

/**
 * Full-bleed viewer for an image attachment.
 *
 * Clicking a thumbnail used to navigate to the file, which nginx serves with
 * `Content-Disposition: attachment` (deliberately - those bytes are never
 * re-encoded, so they're served inert). The effect was that "look closer"
 * silently meant "download". Expanding in place restores the obvious reading
 * and leaves downloading to the button that says so.
 *
 * `<img>` isn't affected by the header - it only applies to navigations - so
 * the same URL renders here without loosening anything server-side.
 */
export function ImageLightbox({
  attachment,
  open,
  onOpenChange,
}: {
  attachment: Attachment;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/80" />
        <RadixDialog.Content
          aria-describedby={undefined}
          className="fixed inset-0 z-50 flex flex-col focus:outline-none"
          // Radix restores focus to the thumbnail on close; without this it
          // also scrolls it back into view, yanking a long channel around.
          onCloseAutoFocus={(e) => e.preventDefault()}
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
              // The stored name is an opaque id, so the real one comes from here.
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

          {/* Clicking the empty space around the image closes, as every other
              viewer does; the image itself must not, or a mis-drag ends it. */}
          <div
            className="flex min-h-0 flex-1 items-center justify-center p-4"
            onClick={() => onOpenChange(false)}
          >
            <img
              src={attachment.url}
              alt={attachment.filename}
              onClick={(e) => e.stopPropagation()}
              className="max-h-full max-w-full object-contain"
            />
          </div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
