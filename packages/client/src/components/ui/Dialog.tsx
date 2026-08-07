import * as RadixDialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "../../lib/cn";

export const Dialog = RadixDialog.Root;
export const DialogClose = RadixDialog.Close;

interface DialogContentProps {
  title: string;
  description?: string;
  children: ReactNode;
  className?: string;
}

/** Dialog shell that takes over the whole viewport instead of floating over it.
 * Opaque by design: nothing of the app behind it stays visible. */
export function DialogFullScreenContent({
  title,
  description,
  children,
  className,
}: DialogContentProps) {
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay className="fixed inset-0 z-40 bg-surface-1" />
      <RadixDialog.Content
        className={cn(
          "fixed inset-0 z-50 flex flex-col bg-surface-1 focus:outline-none",
          className,
        )}
      >
        <RadixDialog.Title className="sr-only">{title}</RadixDialog.Title>
        {description ? (
          <RadixDialog.Description className="sr-only">
            {description}
          </RadixDialog.Description>
        ) : (
          // Radix warns when Content has no Description; opt out explicitly.
          <RadixDialog.Description className="sr-only">
            {title}
          </RadixDialog.Description>
        )}
        {children}
      </RadixDialog.Content>
    </RadixDialog.Portal>
  );
}

/** Styled Radix dialog shell: overlay, centered card, title bar with close. */
export function DialogContent({
  title,
  description,
  children,
  className,
}: DialogContentProps) {
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/60 data-[state=open]:animate-in" />
      <RadixDialog.Content
        className={cn(
          "fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2",
          "max-h-[85dvh] overflow-y-auto rounded-2xl border border-border bg-surface-2 p-5 shadow-2xl focus:outline-none md:p-6",
          className,
        )}
      >
        <RadixDialog.Title className="text-lg font-semibold">
          {title}
        </RadixDialog.Title>
        {description ? (
          <RadixDialog.Description className="mt-1 text-sm text-ink-secondary">
            {description}
          </RadixDialog.Description>
        ) : (
          // Radix warns when Content has no Description; opt out explicitly.
          <RadixDialog.Description className="sr-only">
            {title}
          </RadixDialog.Description>
        )}
        <div className="mt-4">{children}</div>
        <RadixDialog.Close
          aria-label="Close"
          className="absolute right-2 top-2 rounded-lg p-3 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink"
        >
          <X aria-hidden className="size-5" />
        </RadixDialog.Close>
      </RadixDialog.Content>
    </RadixDialog.Portal>
  );
}
