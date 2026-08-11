

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

  label: string;

  description: string;

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
