import { useEffect, useState } from "react";

/** Extensions that can carry more than one frame. */
const ANIMATABLE = /\.(gif|webp|apng|avif)(?:[?#]|$)/i;

/**
 * Nothing larger is ever needed: a still frame stands in for an icon, and the
 * biggest one drawn is 48 CSS px. Capping here keeps the data URL small enough
 * to hold one per server without thinking about it.
 */
const MAX_FRAME_PX = 128;

export function isAnimatable(url: string | null | undefined): boolean {
  return !!url && ANIMATABLE.test(url);
}

/**
 * The first frame of an animated image, as a data URL, or null.
 *
 * An animated icon that plays in a list of thirty of them is thirty things
 * moving in the corner of your eye while you read something else. Swapping in
 * a still frame everywhere but the one you're actually looking at is how every
 * client that has this problem solves it, and the frame has to be painted
 * rather than requested because the icon URL is whatever the server owner
 * pasted in - there is no transformation we can append to all of them.
 *
 * Returns null while loading, for a still image, and when the frame could not
 * be taken: a cross-origin host that sends no CORS headers taints the canvas
 * and `toDataURL` throws. Callers fall back to the animated URL, which is what
 * they would have shown anyway.
 */
export function useStillFrame(url: string | null | undefined): string | null {
  const [frame, setFrame] = useState<string | null>(null);

  useEffect(() => {
    setFrame(null);
    if (!isAnimatable(url)) return;

    let cancelled = false;
    const image = new Image();
    // Without this the pixels are readable but unreadable-back: the canvas is
    // tainted the moment they land on it.
    image.crossOrigin = "anonymous";
    image.onload = () => {
      if (cancelled) return;
      const { naturalWidth: w, naturalHeight: h } = image;
      if (!w || !h) return;
      const scale = Math.min(1, MAX_FRAME_PX / Math.max(w, h));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(w * scale));
      canvas.height = Math.max(1, Math.round(h * scale));
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
      try {
        setFrame(canvas.toDataURL("image/png"));
      } catch {
        // Tainted canvas - leave it animating.
      }
    };
    image.src = url!;

    return () => {
      cancelled = true;
    };
  }, [url]);

  return frame;
}
