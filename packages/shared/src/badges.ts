/**
 * Profile badge catalog. The server stores only the slug on the user row and
 * validates it against this same list (mirrored in services::badge), so labels
 * and copy live here alone — renaming one never needs a migration.
 */

export type BadgeId = 'early_developer' | 'early_member' | 'bonfire';

export interface BadgeDef {
  id: BadgeId;
  /** Short name shown next to the icon. */
  label: string;
  /** Tooltip / settings-list explanation of how it was earned. */
  description: string;
  /** Badge tint as 0xRRGGBB, used for the pill background. */
  color: number;
}

export const BADGES: Record<BadgeId, BadgeDef> = {
  early_developer: {
    id: 'early_developer',
    label: 'Early Developer',
    description: 'Built on OrangChat before it opened up.',
    color: 0xff6a1a,
  },
  early_member: {
    id: 'early_member',
    label: 'Early Member',
    description: 'Joined OrangChat in its first days.',
    color: 0x5b8def,
  },
  bonfire: {
    id: 'bonfire',
    label: 'Bonfire',
    description: 'Was there for the bonfire.',
    color: 0xe2574c,
  },
};

/** Catalog order — how badges are laid out on a profile card. */
export const BADGE_ORDER: BadgeId[] = ['early_developer', 'early_member', 'bonfire'];

export const isBadgeId = (value: string): value is BadgeId => value in BADGES;

/**
 * Drops unknown slugs and puts the rest in catalog order. Guards the client
 * against a server that knows a badge this build doesn't.
 */
export function resolveBadges(slugs: readonly string[]): BadgeDef[] {
  const owned = new Set(slugs.filter(isBadgeId));
  return BADGE_ORDER.filter((id) => owned.has(id)).map((id) => BADGES[id]);
}
