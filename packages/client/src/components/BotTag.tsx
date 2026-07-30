/**
 * The `BOT` label shown beside an automated account's name.
 *
 * A rendered element rather than text appended to the display name: the flag
 * comes from the account itself, so nobody can put "BOT" in a nickname and pass
 * as one. `aria-label` spells it out because the visible text is an all-caps
 * abbreviation a screen reader would otherwise read as letters.
 */
export function BotTag({ className = "" }: { className?: string }) {
  return (
    <span
      aria-label="Bot account"
      className={`shrink-0 rounded bg-primary/15 px-1 py-px text-[10px] font-semibold uppercase leading-tight tracking-wide text-primary ${className}`}
    >
      Bot
    </span>
  );
}
