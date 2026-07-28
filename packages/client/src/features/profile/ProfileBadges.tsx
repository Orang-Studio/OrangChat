import { badgeAsset, resolveBadges } from "@orangchat/shared";
import { cn } from "../../lib/cn";

/**
 * Artwork badges for a profile card, with explanations available on hover and
 * to assistive technology. Each badge is an image served from `/badges/`.
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
    <div className={cn("oc-pf-badges flex flex-wrap items-center gap-1", className)}>
      {resolved.map((badge) => (
        <img
          key={badge.id}
          src={badgeAsset(badge.id)}
          alt={`${badge.label}: ${badge.description}`}
          title={`${badge.label}: ${badge.description}`}
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
      ))}
    </div>
  );
}
