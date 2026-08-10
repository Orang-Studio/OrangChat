import * as RadixDialog from "@radix-ui/react-dialog";
import { useState } from "react";
import { Bookmark, Download, RotateCcw, X, ZoomIn, ZoomOut } from "lucide-react";
import type { Attachment } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { formatBytes } from "./attachments";
import { isGif, isGifFavorite, useFavoriteGifs } from "./favoriteGifs";
import { ImageContextMenu } from "./ImageContextMenu";
import { MediaSenderBar, type MediaContext } from "./MediaSenderBar";
import { useMediaZoom, type MediaOrigin } from "./useMediaZoom";

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
  context,
  origin,
  open,
  onOpenChange,
}: {
  attachment: Attachment;
  /** The message this file came on. Absent where a viewer has no message
   *  behind it, and then the bar along the bottom is simply not drawn. */
  context?: MediaContext;
  /** The thumbnail's box, so the viewer grows out of where it was clicked. */
  origin?: MediaOrigin | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const favoriteGifs = useFavoriteGifs((s) => s.gifs);
  const toggleFavorite = useFavoriteGifs((s) => s.toggle);

  // Only GIFs are bookmarkable - the picker they feed is a GIF picker, so a
  // saved PNG would have nowhere to appear.
  const gif = isGif(attachment.contentType, attachment.filename);
  // Self-hosted/Cloudinary uploads carry a relative url (`/api/attachments/...`).
  // A GIF is sent by putting its url in the message, but the media embed pipeline
  // only renders absolute http(s) urls - so bookmark the resolved absolute form,
  // the same shape a Tenor GIF already has.
  const gifUrl = new URL(attachment.url, window.location.origin).toString();
  const saved = gif && isGifFavorite(favoriteGifs, gifUrl);
  const [scale, setScale] = useState(1);
  const zoom = useMediaZoom(origin);

  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="oc-backdrop fixed inset-0 z-40 bg-black/80" />
        <RadixDialog.Content
          aria-describedby={undefined}
          className="fixed inset-0 z-50 flex flex-col focus:outline-none"
          // Radix restores focus to the thumbnail on close; without this it
          // also scrolls it back into view, yanking a long channel around.
          onCloseAutoFocus={(e) => e.preventDefault()}
        >
          <RadixDialog.Title className="sr-only">{attachment.filename}</RadixDialog.Title>

           <div className="oc-chrome-top absolute inset-x-0 top-0 z-10 flex items-center gap-3 bg-black/40 px-4 py-2.5 text-white">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{attachment.filename}</p>
              {attachment.size > 0 && (
                <p className="text-[11px] text-white/60">{formatBytes(attachment.size)}</p>
              )}
            </div>
            {gif && (
              <button
                type="button"
                aria-pressed={saved}
                aria-label={saved ? "Remove from favourites" : "Favourite this GIF"}
                onClick={() =>
                  toggleFavorite({ url: gifUrl, label: attachment.filename })
                }
                className="flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors hover:bg-white/15"
              >
                <Bookmark
                  aria-hidden
                  className={`size-4 ${saved ? "fill-current" : ""}`}
                />
                {saved ? "Favourited" : "Favourite"}
              </button>
            )}
            <button
              type="button"
              aria-label="Zoom out"
              disabled={scale <= 1}
              onClick={() => setScale((value) => Math.max(1, value - 0.5))}
              className="rounded-lg p-1.5 transition-colors hover:bg-white/15 disabled:opacity-40"
            >
              <ZoomOut aria-hidden className="size-4" />
            </button>
            <button
              type="button"
              aria-label="Reset zoom"
              disabled={scale === 1}
              onClick={() => setScale(1)}
              className="rounded-lg p-1.5 transition-colors hover:bg-white/15 disabled:opacity-40"
            >
              <RotateCcw aria-hidden className="size-4" />
            </button>
            <button
              type="button"
              aria-label="Zoom in"
              disabled={scale >= 4}
              onClick={() => setScale((value) => Math.min(4, value + 0.5))}
              className="rounded-lg p-1.5 transition-colors hover:bg-white/15 disabled:opacity-40"
            >
              <ZoomIn aria-hidden className="size-4" />
            </button>
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
             // The chrome floats over the image, so the space it takes has to
             // come out of the image's box or it sits behind the sender bar.
             className={cn(
               "absolute inset-0 flex items-center justify-center p-4 pt-16",
               context && "pb-32",
             )}
            onClick={() => onOpenChange(false)}
          >
            {/* The zoom wrapper carries the opening move; the image keeps its
                own transform for the zoom buttons, so the two never fight. */}
            <div ref={zoom.ref} className="flex max-h-full max-w-full">
              <ImageContextMenu attachment={attachment}>
                <img
                  src={attachment.url}
                  alt={attachment.filename}
                  onLoad={zoom.onReady}
                  onClick={(e) => e.stopPropagation()}
                  onContextMenu={(e) => e.stopPropagation()}
                  className="max-h-full max-w-full object-contain transition-transform"
                  style={{ transform: `scale(${scale})` }}
                />
              </ImageContextMenu>
            </div>
          </div>

          {context && (
            <MediaSenderBar
              context={context}
              className="oc-chrome-bottom absolute inset-x-0 bottom-0 z-10"
            />
          )}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
