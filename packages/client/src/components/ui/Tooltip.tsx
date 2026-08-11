import * as RadixTooltip from "@radix-ui/react-tooltip";
import type { ReactNode } from "react";

export const TooltipProvider = RadixTooltip.Provider;

interface TooltipProps {
  label: string;
  side?: "top" | "right" | "bottom" | "left";
  children: ReactNode;
}


export function Tooltip({ label, side = "right", children }: TooltipProps) {
  return (
    <RadixTooltip.Root delayDuration={150}>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          side={side}
          sideOffset={8}
          className="z-50 rounded-lg border border-border bg-surface-4 px-2.5 py-1.5 text-sm font-medium shadow-lg"
        >
          {label}
          <RadixTooltip.Arrow className="fill-surface-4" />
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  );
}
