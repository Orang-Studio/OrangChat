import * as RadixDropdown from "@radix-ui/react-dropdown-menu";
import { Check, ChevronRight } from "lucide-react";
import type { ComponentPropsWithoutRef } from "react";
import { cn } from "../../lib/cn";

export const DropdownMenu = RadixDropdown.Root;
export const DropdownMenuTrigger = RadixDropdown.Trigger;
export const DropdownMenuSeparator = (props: ComponentPropsWithoutRef<typeof RadixDropdown.Separator>) => (
  <RadixDropdown.Separator
    {...props}
    className={cn("mx-1 my-1 h-px bg-border", props.className)}
  />
);
export const DropdownMenuLabel = (props: ComponentPropsWithoutRef<typeof RadixDropdown.Label>) => (
  <RadixDropdown.Label
    {...props}
    className={cn(
      "px-2.5 pb-1 pt-1.5 text-xs font-semibold uppercase tracking-wide text-ink-muted",
      props.className,
    )}
  />
);

const contentClass =
  "z-50 min-w-52 rounded-xl border border-border bg-surface-4 p-1.5 shadow-xl";

export function DropdownMenuContent({
  className,
  sideOffset = 4,
  ...props
}: ComponentPropsWithoutRef<typeof RadixDropdown.Content>) {
  return (
    <RadixDropdown.Portal>
      <RadixDropdown.Content
        sideOffset={sideOffset}
        {...props}
        className={cn(contentClass, className)}
      />
    </RadixDropdown.Portal>
  );
}

const itemClass =
  "flex cursor-pointer items-center gap-2 rounded-lg px-2.5 py-2 text-sm outline-none data-[disabled]:cursor-default data-[disabled]:opacity-50";

export function DropdownMenuItem({
  className,
  danger = false,
  ...props
}: ComponentPropsWithoutRef<typeof RadixDropdown.Item> & { danger?: boolean }) {
  return (
    <RadixDropdown.Item
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

export function DropdownMenuCheckboxItem({
  className,
  children,
  ...props
}: ComponentPropsWithoutRef<typeof RadixDropdown.CheckboxItem>) {
  return (
    <RadixDropdown.CheckboxItem
      {...props}
      className={cn(
        itemClass,
        "data-[highlighted]:bg-primary data-[highlighted]:text-ink-on-primary",
        className,
      )}
    >
      <span className="flex size-4 shrink-0 items-center justify-center rounded border border-border-strong">
        <RadixDropdown.ItemIndicator>
          <Check aria-hidden className="size-3.5" />
        </RadixDropdown.ItemIndicator>
      </span>
      {children}
    </RadixDropdown.CheckboxItem>
  );
}

export const DropdownMenuSub = RadixDropdown.Sub;

export function DropdownMenuSubTrigger({
  className,
  children,
  ...props
}: ComponentPropsWithoutRef<typeof RadixDropdown.SubTrigger>) {
  return (
    <RadixDropdown.SubTrigger
      {...props}
      className={cn(
        itemClass,
        "data-[highlighted]:bg-primary data-[highlighted]:text-ink-on-primary data-[state=open]:bg-primary data-[state=open]:text-ink-on-primary",
        className,
      )}
    >
      {children}
      <ChevronRight aria-hidden className="ml-auto size-4" />
    </RadixDropdown.SubTrigger>
  );
}

export function DropdownMenuSubContent({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof RadixDropdown.SubContent>) {
  return (
    <RadixDropdown.Portal>
      <RadixDropdown.SubContent
        sideOffset={6}
        {...props}
        className={cn(contentClass, className)}
      />
    </RadixDropdown.Portal>
  );
}
