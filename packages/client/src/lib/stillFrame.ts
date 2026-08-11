import { useEffect, useState } from "react";


const ANIMATABLE = /\.(gif|webp|apng|avif)(?:[?#]|$)/i;


const MAX_FRAME_PX = 128;

export function isAnimatable(url: string | null | undefined): boolean {
  return !!url && ANIMATABLE.test(url);
}


export function useStillFrame(url: string | null | undefined): string | null {
  const [frame, setFrame] = useState<string | null>(null);

  useEffect(() => {
    setFrame(null);
    if (!isAnimatable(url)) return;

    let cancelled = false;
    const image = new Image();
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
      }
    };
    image.src = url!;

    return () => {
      cancelled = true;
    };
  }, [url]);

  return frame;
}
