import { useState } from "react";
import * as Popover from "@radix-ui/react-popover";
import { SmilePlus } from "lucide-react";
import type { Message } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { QUICK_EMOJIS } from "./emoji-data";
import { toggleReaction } from "./socket-actions";

/**
 * Shared geometry for everything on a message's reactions strip. The fixed
 * height is what keeps the counts and the add button the same size; centring
 * both axes is what keeps their contents on one line.
 */
export const REACTION_CHIP =
  "flex h-7 items-center justify-center rounded-md border transition-colors";

/**
 * Where the strip is drawn. `surface` is a message row in the list; `dark` is
 * chrome over media, where the surface tokens would sink into the black.
 */
export type ReactionTone = "surface" | "dark";

const CHIP_TONE: Record<ReactionTone, { mine: string; theirs: string; picker: string }> = {
  surface: {
    mine: "border-primary bg-primary-soft",
    theirs: "border-border bg-surface-2 hover:border-border-strong",
    picker: "border-border bg-surface-2 text-ink-muted hover:border-border-strong hover:text-ink",
  },
  dark: {
    mine: "border-white/70 bg-white/25 text-white",
    theirs: "border-white/20 bg-white/10 text-white hover:border-white/40",
    picker: "border-white/20 bg-white/10 text-white/80 hover:border-white/40 hover:text-white",
  },
};

/**
 * `className` lets the same picker be the icon in the hover toolbar or, with
 * `REACTION_CHIP`, the trailing chip on the reactions strip - where it has to
 * measure exactly like the count chips beside it.
 */
export function ReactionPicker({
  message,
  className,
  children,
}: {
  message: Message;
  className?: string;
  /** Label beside the icon, for the places that spell the action out. */
  children?: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger
        aria-label="Add reaction"
        className={
          className ??
          "rounded p-1.5 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink md:p-1"
        }
      >
        <SmilePlus aria-hidden className="size-4" />
        {children}
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          side="top"
          sideOffset={4}
          className="z-50 flex gap-0.5 rounded-xl border border-border bg-surface-4 p-1.5 shadow-xl"
        >
          {QUICK_EMOJIS.map((emoji) => {
            const mine = message.reactions.some((r) => r.emoji === emoji && r.me);
            return (
              <button
                key={emoji}
                type="button"
                onClick={() => {
                  toggleReaction(
                    { channelId: message.channelId, messageId: message.id, emoji },
                    mine,
                  );
                  setOpen(false);
                }}
                className={cn(
                  "rounded-lg p-1.5 text-lg leading-none transition-colors hover:bg-surface-2",
                  mine && "bg-primary-soft",
                )}
              >
                {emoji}
              </button>
            );
          })}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

/**
 * The counts and the trailing add button as one strip, so they share
 * REACTION_CHIP's height and shape - letting each size to its own content left
 * the add button sitting a few pixels off the row it belongs to.
 *
 * In a message row the strip is nothing at all until someone reacts; over media
 * there is no hover toolbar to reach for, so `alwaysPicker` keeps a labelled
 * "React" chip there as the only way in.
 */
export function ReactionStrip({
  message,
  tone = "surface",
  alwaysPicker = false,
  className,
}: {
  message: Message;
  tone?: ReactionTone;
  alwaysPicker?: boolean;
  className?: string;
}) {
  const chip = CHIP_TONE[tone];
  if (message.reactions.length === 0 && !alwaysPicker) return null;

  return (
    <div className={cn("flex flex-wrap items-center gap-1", className)}>
      {message.reactions.map((r) => (
        <button
          key={r.emoji}
          type="button"
          aria-pressed={r.me}
          onClick={() =>
            toggleReaction(
              { channelId: message.channelId, messageId: message.id, emoji: r.emoji },
              r.me,
            )
          }
          className={cn(REACTION_CHIP, "gap-1 px-2 text-sm", r.me ? chip.mine : chip.theirs)}
        >
          <span>{r.emoji}</span>
          <span
            className={cn(
              "text-xs font-semibold",
              tone === "dark" ? "text-white/80" : "text-ink-secondary",
            )}
          >
            {r.count}
          </span>
        </button>
      ))}
      <ReactionPicker
        message={message}
        className={cn(
          REACTION_CHIP,
          chip.picker,
          message.reactions.length === 0 ? "gap-1.5 px-2.5 text-xs font-medium" : "w-9",
        )}
      >
        {message.reactions.length === 0 ? "React" : null}
      </ReactionPicker>
    </div>
  );
}
