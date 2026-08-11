import { useCallback, useLayoutEffect, useRef } from "react";


export interface MediaOrigin {
  top: number;
  left: number;
  width: number;
  height: number;
}

const EASE = "cubic-bezier(0.2, 0, 0, 1)";
const DURATION = 220;


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
