/**
 * Permission bitfield — Discord-style. Each permission is a single bit in a
 * BigInt. A member's effective permissions are the bitwise-OR of all their
 * roles' permission bitfields (plus channel overwrites, applied server-side).
 */
export const Permissions = {
  /** Bypass all permission checks. Server owner always has this implicitly. */
  ADMINISTRATOR: 1n << 0n,

  // Server management
  MANAGE_SERVER: 1n << 1n,
  MANAGE_ROLES: 1n << 2n,
  MANAGE_CHANNELS: 1n << 3n,
  MANAGE_INVITES: 1n << 4n,

  // Membership / moderation
  KICK_MEMBERS: 1n << 5n,
  BAN_MEMBERS: 1n << 6n,
  MANAGE_NICKNAMES: 1n << 7n,
  MANAGE_MESSAGES: 1n << 8n,

  // Text
  VIEW_CHANNEL: 1n << 9n,
  SEND_MESSAGES: 1n << 10n,
  EMBED_LINKS: 1n << 11n,
  ATTACH_FILES: 1n << 12n,
  ADD_REACTIONS: 1n << 13n,
  MENTION_EVERYONE: 1n << 14n,
  READ_MESSAGE_HISTORY: 1n << 15n,

  // Voice
  CONNECT: 1n << 16n,
  SPEAK: 1n << 17n,
  VIDEO: 1n << 18n,
  SCREEN_SHARE: 1n << 19n,
  MUTE_MEMBERS: 1n << 20n,
  DEAFEN_MEMBERS: 1n << 21n,
  MOVE_MEMBERS: 1n << 22n,

  /** Time a member out: no sending, reacting or speaking until it expires. */
  MODERATE_MEMBERS: 1n << 23n,
  VIEW_AUDIT_LOG: 1n << 24n,

  // Expression
  /** Upload, rename and delete the server's custom emoji and soundboard sounds. */
  MANAGE_EXPRESSIONS: 1n << 25n,
} as const;

export type PermissionName = keyof typeof Permissions;

/** Sensible default permissions granted to the @everyone role on a new server. */
export const DEFAULT_EVERYONE_PERMISSIONS =
  Permissions.VIEW_CHANNEL |
  Permissions.SEND_MESSAGES |
  Permissions.EMBED_LINKS |
  Permissions.ATTACH_FILES |
  Permissions.ADD_REACTIONS |
  Permissions.READ_MESSAGE_HISTORY |
  Permissions.CONNECT |
  Permissions.SPEAK |
  Permissions.VIDEO |
  Permissions.SCREEN_SHARE;

/** Every permission bit set — the owner/admin superset. */
export const ALL_PERMISSIONS = Object.values(Permissions).reduce(
  (acc, bit) => acc | bit,
  0n,
);

/** Combine an array of role bitfields into one effective bitfield. */
export function combinePermissions(bitfields: bigint[]): bigint {
  return bitfields.reduce((acc, b) => acc | b, 0n);
}

/**
 * True if `permissions` grants `required`. ADMINISTRATOR short-circuits to true.
 * `required` may be a single permission or a combined bitfield.
 */
export function hasPermission(permissions: bigint, required: bigint): boolean {
  if ((permissions & Permissions.ADMINISTRATOR) === Permissions.ADMINISTRATOR) {
    return true;
  }
  return (permissions & required) === required;
}

/** Expand a bitfield into the list of permission names it contains. */
export function permissionNames(permissions: bigint): PermissionName[] {
  return (Object.keys(Permissions) as PermissionName[]).filter(
    (name) => (permissions & Permissions[name]) === Permissions[name],
  );
}

/**
 * Permission bitfields are serialized as decimal strings over the wire (JSON has
 * no BigInt). Use these at the transport boundary.
 */
export function serializePermissions(permissions: bigint): string {
  return permissions.toString();
}

export function parsePermissions(value: string): bigint {
  return BigInt(value);
}
