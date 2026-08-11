import * as Popover from "@radix-ui/react-popover";
import { badgeAsset, resolveBadges } from "@orangchat/shared";
import { cn } from "../../lib/cn";


export function ProfileBadges({
  badges,
  className,
}: {
  badges: readonly string[];
  className?: string;
}) {
  const resolved = resolveBadges(badges);
  if (resolved.length === 0) return null;

  return (
    <div className={cn("oc-pf-badges flex flex-wrap items-center gap-1", className)}>
      {resolved.map((badge) => {
        const explanation = `${badge.label}: ${badge.description}`;

        return (
          <Popover.Root key={badge.id}>
            <Popover.Trigger asChild>
              <button
                type="button"
                aria-label={explanation}
                className="flex size-5 shrink-0 items-center justify-center rounded focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
              >
                <img
                  src={badgeAsset(badge.id)}
                  alt=""
                  title={explanation}
                  width={20}
                  height={20}
                  loading="lazy"
                  draggable={false}
                  data-badge={badge.id}
                  className={cn(
                    "oc-pf-badge size-5 shrink-0 select-none object-contain",
                    `oc-pf-badge-${badge.id.replace(/_/g, "-")}`,
                  )}
                />
              </button>
            </Popover.Trigger>
            <Popover.Portal>
              <Popover.Content
                side="top"
                sideOffset={6}
                collisionPadding={8}
                className="z-50 rounded-lg border border-border bg-surface-4 px-2.5 py-1.5 text-xs font-medium text-ink shadow-lg"
              >
                {badge.label}
                <Popover.Arrow className="fill-surface-4" />
              </Popover.Content>
            </Popover.Portal>
          </Popover.Root>
        );
      })}
    </div>
  );
}
