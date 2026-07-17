import * as RadixTabs from "@radix-ui/react-tabs";
import type { ComponentPropsWithoutRef } from "react";
import { cn } from "../../lib/cn";

export const Tabs = RadixTabs.Root;
export const TabsContent = RadixTabs.Content;

export function TabsList({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof RadixTabs.List>) {
  return (
    <RadixTabs.List
      {...props}
      className={cn("flex gap-1 border-b border-border pb-2", className)}
    />
  );
}

export function TabsTrigger({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof RadixTabs.Trigger>) {
  return (
    <RadixTabs.Trigger
      {...props}
      className={cn(
        "rounded-lg px-3 py-1.5 text-sm font-medium text-ink-secondary transition-colors",
        "hover:bg-surface-3 hover:text-ink",
        "data-[state=active]:bg-primary-soft data-[state=active]:text-primary",
        className,
      )}
    />
  );
}
