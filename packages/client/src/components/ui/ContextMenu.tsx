import * as RadixContextMenu from "@radix-ui/react-context-menu";
import { Check, ChevronRight } from "lucide-react";
import type { ComponentPropsWithoutRef } from "react";
import { cn } from "../../lib/cn";

/**
 * Right-click menu, styled to match the click-triggered DropdownMenu so both
 * read as the same surface. Radix handles cursor positioning, focus trapping
 * and dismissal (Escape / outside click / scroll).
 */
export const ContextMenu = RadixContextMenu.Root;
export const ContextMenuTrigger = RadixContextMenu.Trigger;

export const ContextMenuSeparator = (
  props: ComponentPropsWithoutRef<typeof RadixContextMenu.Separator>,
) => (
  <RadixContextMenu.Separator
    {...props}
    className={cn("mx-1 my-1 h-px bg-border", props.className)}
  />
);

export const ContextMenuLabel = (
  props: ComponentPropsWithoutRef<typeof RadixContextMenu.Label>,
) => (
  <RadixContextMenu.Label
    {...props}
    className={cn(
      "px-2.5 pb-1 pt-1.5 text-xs font-semibold uppercase tracking-wide text-ink-muted",
      props.className,
    )}
  />
);

const contentClass =
  "oc-menu-surface z-50 min-w-52 rounded-xl border border-border bg-surface-4 p-1.5 shadow-xl";

export function ContextMenuContent({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof RadixContextMenu.Content>) {
  return (
    <RadixContextMenu.Portal>
      <RadixContextMenu.Content {...props} className={cn(contentClass, className)} />
    </RadixContextMenu.Portal>
  );
}

const itemClass =
  "flex cursor-pointer items-center gap-2 rounded-lg px-2.5 py-2 text-sm outline-none data-[disabled]:cursor-default data-[disabled]:opacity-50";

export function ContextMenuItem({
  className,
  danger = false,
  ...props
}: ComponentPropsWithoutRef<typeof RadixContextMenu.Item> & { danger?: boolean }) {
  return (
    <RadixContextMenu.Item
      {...props}
      className={cn(
        itemClass,
        danger
          ? "text-danger data-[highlighted]:bg-danger data-[highlighted]:text-white"
          : "data-[highlighted]:bg-primary data-[highlighted]:text-ink-on-primary",
        className,
      )}
    />
  );
}

export function ContextMenuCheckboxItem({
  className,
  children,
  ...props
}: ComponentPropsWithoutRef<typeof RadixContextMenu.CheckboxItem>) {
  return (
    <RadixContextMenu.CheckboxItem
      {...props}
      className={cn(
        itemClass,
        "data-[highlighted]:bg-primary data-[highlighted]:text-ink-on-primary",
        className,
      )}
    >
      <span className="flex size-4 shrink-0 items-center justify-center rounded border border-border-strong">
        <RadixContextMenu.ItemIndicator>
          <Check aria-hidden className="size-3.5" />
        </RadixContextMenu.ItemIndicator>
      </span>
      {children}
    </RadixContextMenu.CheckboxItem>
  );
}

export const ContextMenuSub = RadixContextMenu.Sub;

export function ContextMenuSubTrigger({
  className,
  children,
  ...props
}: ComponentPropsWithoutRef<typeof RadixContextMenu.SubTrigger>) {
  return (
    <RadixContextMenu.SubTrigger
      {...props}
      className={cn(
        itemClass,
        "data-[highlighted]:bg-primary data-[highlighted]:text-ink-on-primary data-[state=open]:bg-primary data-[state=open]:text-ink-on-primary",
        className,
      )}
    >
      {children}
      <ChevronRight aria-hidden className="ml-auto size-4" />
    </RadixContextMenu.SubTrigger>
  );
}

export function ContextMenuSubContent({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof RadixContextMenu.SubContent>) {
  return (
    <RadixContextMenu.Portal>
      <RadixContextMenu.SubContent
        {...props}
        className={cn(contentClass, className)}
      />
    </RadixContextMenu.Portal>
  );
}
