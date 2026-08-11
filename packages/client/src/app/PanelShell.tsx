import { useEffect, useRef, useState, type ReactNode, type TouchEvent } from "react";
import { useLocation } from "react-router-dom";
import { cn } from "../lib/cn";
import { ServerRail } from "../features/servers/ServerRail";
import { panelActions, usePanelStore } from "../stores/panels";

const SWIPE_MIN_X = 56;

const MOBILE_QUERY = "(max-width: 767px)";
const BELOW_LG_QUERY = "(max-width: 1023px)";
const isMobile = () => window.matchMedia(MOBILE_QUERY).matches;
const isBelowLg = () => window.matchMedia(BELOW_LG_QUERY).matches;

interface PanelShellProps {

  sidebar?: ReactNode;

  aside?: ReactNode;
  children: ReactNode;
}


export function PanelShell({ sidebar, aside, children }: PanelShellProps) {
  const left = usePanelStore((s) => s.left);
  const right = usePanelStore((s) => s.right);
  const { pathname } = useLocation();
  const touchStart = useRef<{ x: number; y: number } | null>(null);
  const leftRef = useRef<HTMLDivElement>(null);
  const rightRef = useRef<HTMLDivElement>(null);

  const [mobile, setMobile] = useState(isMobile);
  const [belowLg, setBelowLg] = useState(isBelowLg);
  useEffect(() => {
    const mqMobile = window.matchMedia(MOBILE_QUERY);
    const mqLg = window.matchMedia(BELOW_LG_QUERY);
    const apply = () => {
      setMobile(mqMobile.matches);
      setBelowLg(mqLg.matches);
    };
    apply();
    mqMobile.addEventListener("change", apply);
    mqLg.addEventListener("change", apply);
    return () => {
      mqMobile.removeEventListener("change", apply);
      mqLg.removeEventListener("change", apply);
    };
  }, []);

  useEffect(() => {
    if (leftRef.current) leftRef.current.inert = !left && mobile;
    if (rightRef.current) rightRef.current.inert = !right && belowLg;
  }, [left, right, mobile, belowLg]);

  useEffect(() => {
    panelActions.closeAll();
  }, [pathname]);

  const onTouchStart = (e: TouchEvent) => {
    const t = e.touches[0];
    touchStart.current = t ? { x: t.clientX, y: t.clientY } : null;
  };

  const onTouchEnd = (e: TouchEvent) => {
    const start = touchStart.current;
    touchStart.current = null;
    const t = e.changedTouches[0];
    if (!start || !t) return;
    const dx = t.clientX - start.x;
    const dy = t.clientY - start.y;
    if (Math.abs(dx) < SWIPE_MIN_X || Math.abs(dx) < Math.abs(dy) * 2) return;

    if (dx > 0) {
      if (right) panelActions.closeAll();
      else if (isMobile()) panelActions.openLeft();
    } else {
      if (left) panelActions.closeAll();
      else if (aside && isBelowLg()) panelActions.openRight();
    }
  };

  return (
    <div
      className="flex min-w-0 flex-1"
      onTouchStart={onTouchStart}
      onTouchEnd={onTouchEnd}
    >
      <div
        ref={leftRef}
        aria-hidden={!left && mobile}
        className={cn(
          "fixed top-0 left-0 z-40 flex h-[var(--oc-vvh,100dvh)] pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)] transition-transform duration-200 ease-out",
          "md:static md:z-auto md:h-auto md:translate-x-0 md:pb-0 md:pt-0 md:transition-none",
          left ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <ServerRail />
        {sidebar}
      </div>

      <div className="flex min-w-0 flex-1">{children}</div>

      {aside && (
        <div
          ref={rightRef}
          aria-hidden={!right && belowLg}
          className={cn(
            "fixed top-0 right-0 z-40 flex h-[var(--oc-vvh,100dvh)] pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)] transition-transform duration-200 ease-out",
            "lg:static lg:z-auto lg:h-auto lg:translate-x-0 lg:pb-0 lg:pt-0 lg:transition-none",
            right ? "translate-x-0" : "translate-x-full",
          )}
        >
          {aside}
        </div>
      )}

      <div
        aria-hidden
        onClick={panelActions.closeAll}
        className={cn(
          "fixed inset-0 z-30 bg-black/60 transition-opacity duration-200 lg:hidden",
          left || right ? "opacity-100" : "pointer-events-none opacity-0",
        )}
      />
    </div>
  );
}
