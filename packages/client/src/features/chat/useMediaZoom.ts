import { useCallback, useLayoutEffect, useRef } from "react";

/** Where the thumbnail was when it was clicked, in viewport coordinates. */
export interface MediaOrigin {
  top: number;
  left: number;
  width: number;
  height: number;
}

const EASE = "cubic-bezier(0.2, 0, 0, 1)";
const DURATION = 220;

/**
 * Grow a full-screen image or video out of the thumbnail that opened it.
 *
 * A dialog that slides in from an edge says "a panel arrived"; what actually
 * happened is that one picture got bigger, and the eye should be able to follow
 * it there. So the viewer lays out where it belongs, is snapped back onto the
 * thumbnail's box for one frame, and released - the standard FLIP, which keeps
 * the animation on the compositor and needs no measurements of its own.
 *
 * `onReady` exists because the media often has no size on the first layout: an
 * uncached image lays out at zero until its bytes arrive, and there is nothing
 * to animate from until then. Calling it from `onLoad` plays the same move once
 * the real box exists; whichever call finds a size first wins, and the other
 * does nothing.
 */
export function useMediaZoom(origin?: MediaOrigin | null) {
  const ref = useRef<HTMLDivElement>(null);
  const played = useRef(false);

  const play = useCallback(() => {
    const node = ref.current;
    if (!node || played.current || !origin || origin.width <= 0 || origin.height <= 0) return;
    const rect = node.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    played.current = true;

    const scaleX = origin.width / rect.width;
    const scaleY = origin.height / rect.height;
    const dx = origin.left + origin.width / 2 - (rect.left + rect.width / 2);
    const dy = origin.top + origin.height / 2 - (rect.top + rect.height / 2);

    node.style.transition = "none";
    node.style.transform = `translate(${dx}px, ${dy}px) scale(${scaleX}, ${scaleY})`;
    // Two frames: one for the browser to take the start state, one to leave it.
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        if (!ref.current) return;
        ref.current.style.transition = `transform ${DURATION}ms ${EASE}`;
        ref.current.style.transform = "";
      });
    });
  }, [origin]);

  useLayoutEffect(() => {
    play();
  }, [play]);

  return { ref, onReady: play };
}
