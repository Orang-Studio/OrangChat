import { Code2, Flame, Sparkles } from "lucide-react";
import { resolveBadges, type BadgeId } from "@orangchat/shared";
import { cn } from "../../lib/cn";

/** Catalog slug → icon. Lives here rather than in shared/, which stays free of
 * any dependency on the client's icon set. */
export const BADGE_ICON: Record<BadgeId, typeof Flame> = {
  early_developer: Code2,
  early_member: Sparkles,
  bonfire: Flame,
};

const hex = (color: number) => `#${color.toString(16).padStart(6, "0")}`;

/**
 * Badge pills for a profile card. Each is tinted with its catalog colour over a
 * low-alpha fill of the same hue, so the row reads as one set in both themes.
 * Renders nothing when the user has no badges.
 */
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
    <div className={cn("oc-pf-badges flex flex-wrap gap-1.5", className)}>
      {resolved.map((badge) => {
        const Icon = BADGE_ICON[badge.id];
        const color = hex(badge.color);
        return (
          <span
            key={badge.id}
            // The pill is decorative next to its own label, so the tooltip
            // carries the "how it was earned" copy rather than a bare repeat.
            title={badge.description}
            className="oc-pf-badge flex items-center gap-1 rounded-md border px-1.5 py-0.5 text-xs font-medium"
            style={{
              color,
              borderColor: `${color}59`,
              backgroundColor: `${color}1f`,
            }}
          >
            <Icon aria-hidden className="size-3.5 shrink-0" />
            {badge.label}
          </span>
        );
      })}
    </div>
  );
}
