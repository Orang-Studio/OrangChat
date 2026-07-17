import { useState } from "react";
import * as Popover from "@radix-ui/react-popover";
import { Music } from "lucide-react";
import { cn } from "../../lib/cn";
import { useVoiceStore } from "../voice/store";
import { playSound, useSounds } from "./queries";

/**
 * The soundboard, as a popover off the voice controls.
 *
 * Only offered inside a server voice channel: the board belongs to a server, and
 * a DM call has none.
 */
export function SoundboardPanel({ className }: { className?: string }) {
  const session = useVoiceStore((s) => s.session);
  const [open, setOpen] = useState(false);
  const { data: sounds, isLoading } = useSounds(open ? (session?.serverId ?? undefined) : undefined);

  if (!session?.serverId) return null;
  const channelId = session.channelId;

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button
          type="button"
          aria-label="Soundboard"
          title="Soundboard"
          className={cn(
            "rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-2 hover:text-ink",
            className,
          )}
        >
          <Music aria-hidden className="size-4" />
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          side="top"
          align="end"
          sideOffset={8}
          className="z-50 w-[min(20rem,calc(100vw-1.5rem))] rounded-xl border border-border bg-surface-4 p-2 shadow-2xl"
        >
          <h3 className="px-1 pb-2 text-[11px] font-semibold uppercase tracking-wide text-ink-muted">
            Soundboard
          </h3>
          {isLoading ? (
            <p className="px-1 py-6 text-center text-xs text-ink-muted">Loading…</p>
          ) : sounds && sounds.length > 0 ? (
            <div className="grid max-h-64 grid-cols-3 gap-1.5 overflow-y-auto">
              {sounds.map((sound) => (
                <button
                  key={sound.id}
                  type="button"
                  onClick={() => playSound(channelId, sound.id)}
                  title={`${sound.name} · ${sound.duration.toFixed(1)}s`}
                  className="flex aspect-square flex-col items-center justify-center gap-1 rounded-lg border border-border bg-surface-2 p-1 transition-colors hover:border-primary hover:bg-surface-3"
                >
                  <span aria-hidden className="text-xl leading-none">
                    {sound.emoji ?? "🔊"}
                  </span>
                  <span className="w-full truncate text-center text-[10px] text-ink-secondary">
                    {sound.name}
                  </span>
                </button>
              ))}
            </div>
          ) : (
            <p className="px-2 py-6 text-center text-xs text-ink-muted">
              No sounds yet. Add some in Server settings → Sounds.
            </p>
          )}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
