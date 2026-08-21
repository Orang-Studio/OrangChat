import { useMemo, useState } from "react";
import * as Popover from "@radix-ui/react-popover";
import { Plus, SmilePlus } from "lucide-react";
import { EMOJI_TOKEN_SOURCE, emojiToken, type Emoji, type Message } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { useEmojiMap, useUsableEmojis, withMessageEmojis } from "../emojis/queries";
import { QUICK_EMOJIS } from "./emoji-data";
import { EmojiPickerPanel, resolveRecent, type EmojiPick } from "./EmojiPicker";
import { useRecentEmojis } from "./recentEmojis";
import { toggleReaction } from "./socket-actions";
import { t } from "../../lib/i18n";


const REACTION_CHIP =
  "flex h-7 items-center justify-center rounded-md border transition-colors";

const TOKEN = new RegExp(`^${EMOJI_TOKEN_SOURCE}$`, "i");

export const QUICK_PICK_COUNT = 6;


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

export function reactionValue(pick: EmojiPick): string {
  return pick.custom ? emojiToken(pick.custom) : pick.insert;
}

export function reactWith(message: Message, pick: EmojiPick): void {
  const emoji = reactionValue(pick);
  useRecentEmojis.getState().record(pick.insert);
  toggleReaction(
    { channelId: message.channelId, messageId: message.id, emoji },
    message.reactions.some((r) => r.emoji === emoji && r.me),
  );
}

/**
 * The emoji this viewer reaches for, newest first, topped up with the standard
 * set so a fresh account still gets a full bar.
 */
export function useReactionQuickPicks(limit = QUICK_PICK_COUNT): EmojiPick[] {
  const recent = useRecentEmojis((s) => s.emojis);
  const { data: custom } = useUsableEmojis();
  return useMemo(() => {
    const picks = resolveRecent(recent, custom ?? []);
    const seen = new Set(picks.map((pick) => pick.insert));
    for (const emoji of QUICK_EMOJIS) {
      if (picks.length >= limit) break;
      if (seen.has(emoji)) continue;
      seen.add(emoji);
      picks.push({ insert: emoji });
    }
    return picks.slice(0, limit);
  }, [recent, custom, limit]);
}

/**
 * One reaction as it should be drawn: an image for a custom emoji this viewer
 * can see, the bare `:name:` for one they can't, plain text otherwise.
 */
export function ReactionEmoji({
  emoji,
  emojis,
  className,
}: {
  emoji: string;
  emojis: Record<string, Emoji>;
  className?: string;
}) {
  const match = TOKEN.exec(emoji);
  if (!match) return <span className={className}>{emoji}</span>;

  const custom = emojis[match[3] ?? ""];
  if (!custom) return <span className={className}>:{match[2]}:</span>;
  return (
    <img
      src={custom.url}
      alt={`:${custom.name}:`}
      title={`:${custom.name}:`}
      loading="lazy"
      className={cn("size-[1.15em] object-contain", className)}
    />
  );
}

function useReactionEmojis(message: Message): Record<string, Emoji> {
  const usable = useEmojiMap();
  return useMemo(() => withMessageEmojis(usable, message.emojis), [usable, message.emojis]);
}

/**
 * The two tiers, in one surface: the emoji you actually use, one click away,
 * and a `+` that turns the same popover into the full searchable picker.
 */
function ReactionMenu({ message, onDone }: { message: Message; onDone: () => void }) {
  const [expanded, setExpanded] = useState(false);
  const quick = useReactionQuickPicks();
  const emojis = useReactionEmojis(message);

  if (expanded) {
    return (
      <div className="flex h-[22rem] w-[min(20rem,calc(100vw-2rem))] flex-col">
        <EmojiPickerPanel
          autoFocus
          onPick={(pick) => {
            reactWith(message, pick);
            onDone();
          }}
        />
      </div>
    );
  }

  return (
    <div className="flex items-center gap-0.5">
      {quick.map((pick) => {
        const value = reactionValue(pick);
        const mine = message.reactions.some((r) => r.emoji === value && r.me);
        return (
          <button
            key={value}
            type="button"
            title={pick.custom ? `:${pick.custom.name}:` : pick.insert}
            onClick={() => {
              reactWith(message, pick);
              onDone();
            }}
            className={cn(
              "grid size-9 place-items-center rounded-lg text-lg leading-none transition-colors hover:bg-surface-2",
              mine && "bg-primary-soft",
            )}
          >
            <ReactionEmoji emoji={value} emojis={emojis} />
          </button>
        );
      })}
      <span aria-hidden className="mx-0.5 h-6 w-px bg-border" />
      <button
        type="button"
        aria-label={t("reactions.moreEmoji")}
        title={t("reactions.moreEmoji")}
        onClick={() => setExpanded(true)}
        className="grid size-9 place-items-center rounded-lg text-ink-muted transition-colors hover:bg-surface-2 hover:text-ink"
      >
        <Plus aria-hidden className="size-5" />
      </button>
    </div>
  );
}

const MENU_SURFACE =
  "z-50 rounded-xl border border-border bg-surface-4 p-1.5 shadow-xl";

/**
 * The picker without a trigger of its own, for callers that already have
 * something to hang it on - the message row a right-click landed in, say.
 * Render inside your own `Popover.Root` with a `Popover.Anchor`.
 */
export function ReactionPopoverContent({
  message,
  onDone,
}: {
  message: Message;
  onDone: () => void;
}) {
  return (
    <Popover.Portal>
      <Popover.Content side="top" align="end" sideOffset={4} className={MENU_SURFACE}>
        <ReactionMenu message={message} onDone={onDone} />
      </Popover.Content>
    </Popover.Portal>
  );
}


export function ReactionPicker({
  message,
  className,
  children,
}: {
  message: Message;
  className?: string;

  children?: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger
        aria-label={t("reactions.addReaction")}
        className={
          className ??
          "rounded p-1.5 text-ink-muted transition-colors hover:bg-surface-3 hover:text-ink md:p-1"
        }
      >
        <SmilePlus aria-hidden className="size-4" />
        {children}
      </Popover.Trigger>
      <ReactionPopoverContent message={message} onDone={() => setOpen(false)} />
    </Popover.Root>
  );
}


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
  const emojis = useReactionEmojis(message);
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
          <ReactionEmoji emoji={r.emoji} emojis={emojis} />
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
        {message.reactions.length === 0 ? t("reactions.react") : null}
      </ReactionPicker>
    </div>
  );
}
