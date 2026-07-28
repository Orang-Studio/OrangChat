/**
 * Profile badge catalog. The server stores only the slug on the user row and
 * validates it against this same list (mirrored in services::badge), so labels
 * and copy live here alone - renaming one never needs a migration.
 *
 * Each badge is a piece of artwork served from `/badges/<id>.svg` (and a `.png`
 * fallback), so the clients render an image rather than an icon glyph.
 */

export type BadgeId =
  | 'beta'
  | 'founder'
  | 'developer'
  | 'bughunter'
  | 'contributor'
  | 'bonfire'
  | 'bot';

export interface BadgeDef {
  id: BadgeId;
  /** Short name shown next to the icon. */
  label: string;
  /** Tooltip / settings-list explanation of how it was earned. */
  description: string;
  /** Representative tint as 0xRRGGBB, used where the artwork can't be shown. */
  color: number;
}

export const BADGES: Record<BadgeId, BadgeDef> = {
  beta: {
    id: 'beta',
    label: 'Beta',
    description: 'Here since the beta.',
    color: 0x001fb0,
  },
  founder: {
    id: 'founder',
    label: 'Founder',
    description: 'Was here at the very beginning.',
    color: 0x0090ce,
  },
  developer: {
    id: 'developer',
    label: 'Developer',
    description: 'Builds and maintains OrangChat.',
    color: 0x5865f2,
  },
  bughunter: {
    id: 'bughunter',
    label: 'Bug Hunter',
    description: 'Tracked down bugs in OrangChat.',
    color: 0x2ecc5f,
  },
  contributor: {
    id: 'contributor',
    label: 'Contributor',
    description: 'Contributed to OrangChat.',
    color: 0xf1c40f,
  },
  bonfire: {
    id: 'bonfire',
    label: 'Bonfire',
    description: 'Was there for the bonfire.',
    color: 0xe2574c,
  },
  bot: {
    id: 'bot',
    label: 'Bot',
    description: 'An automated account.',
    color: 0x8b93a7,
  },
};

/** Catalog order - how badges are laid out on a profile card. */
export const BADGE_ORDER: BadgeId[] = [
  'beta',
  'founder',
  'developer',
  'bughunter',
  'contributor',
  'bonfire',
  'bot',
];

export const isBadgeId = (value: string): value is BadgeId => value in BADGES;

/** Path to a badge's artwork, served from the app's static assets. */
export const badgeAsset = (id: BadgeId, ext: 'svg' | 'png' = 'svg'): string =>
  `/badges/${id}.${ext}`;

/**
 * Drops unknown slugs and puts the rest in catalog order. Guards the client
 * against a server that knows a badge this build doesn't.
 */
export function resolveBadges(slugs: readonly string[]): BadgeDef[] {
  const owned = new Set(slugs.filter(isBadgeId));
  return BADGE_ORDER.filter((id) => owned.has(id)).map((id) => BADGES[id]);
}
