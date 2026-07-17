import { z } from 'zod';

/**
 * Zod schemas for request/payload validation. Shared so the server validates
 * and the client's forms (React Hook Form + Zod) enforce the same rules.
 */

export const usernameSchema = z
  .string()
  .min(2, 'Username must be at least 2 characters')
  .max(32, 'Username must be at most 32 characters')
  .regex(/^[a-z0-9_.]+$/, 'Use lowercase letters, numbers, underscores, and dots');

export const passwordSchema = z
  .string()
  .min(8, 'Password must be at least 8 characters')
  .max(200, 'Password is too long');

export const displayNameSchema = z.string().min(1).max(64);

export const presenceStatusSchema = z.enum(['online', 'idle', 'dnd', 'offline']);

// ── Auth ────────────────────────────────────────────────
export const signupSchema = z.object({
  email: z.string().email(),
  username: usernameSchema,
  displayName: displayNameSchema.optional(),
  password: passwordSchema,
});
export type SignupInput = z.infer<typeof signupSchema>;

export const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
  /** Required only once the account has 2FA on; a recovery code also works. */
  totpCode: z.string().min(1).max(32).optional(),
});
export type LoginInput = z.infer<typeof loginSchema>;

export const dmPrivacySchema = z.enum(['everyone', 'friends', 'none']);
export const friendRequestPrivacySchema = z.enum(['everyone', 'mutual', 'none']);

// ── Profile ─────────────────────────────────────────────
// At least one field must be present; each is validated independently.
export const updateProfileSchema = z
  .object({
    username: usernameSchema.optional(),
    displayName: displayNameSchema.optional(),
    avatarUrl: z.string().url().nullable().optional(),
    status: presenceStatusSchema.optional(),
    // ── Discord-style profile ──
    bio: z.string().max(4000).nullable().optional(),
    bannerUrl: z.string().url().nullable().optional(),
    accentColor: z.number().int().min(0).max(0xffffff).nullable().optional(),
    pronouns: z.string().max(40).nullable().optional(),
    // The user's own client CSS override (private; sent only to the owner).
    customCss: z.string().max(100_000).nullable().optional(),
    // Public, sandboxed CSS that themes the user's profile card.
    profileCss: z.string().max(100_000).nullable().optional(),
    // ── Privacy ──
    dmPrivacy: dmPrivacySchema.optional(),
    friendRequestPrivacy: friendRequestPrivacySchema.optional(),
    typingIndicators: z.boolean().optional(),
  })
  .refine((data) => Object.keys(data).length > 0, { message: 'No fields to update' });
export type UpdateProfileInput = z.infer<typeof updateProfileSchema>;

// ── Friends ─────────────────────────────────────────────
export const sendFriendRequestSchema = z.object({
  username: usernameSchema,
});
export type SendFriendRequestInput = z.infer<typeof sendFriendRequestSchema>;

// ── Servers & channels ──────────────────────────────────
export const createServerSchema = z.object({
  name: z.string().min(1).max(100),
  iconUrl: z.string().url().optional(),
});
export type CreateServerInput = z.infer<typeof createServerSchema>;

export const updateServerSchema = z
  .object({
    name: z.string().min(1).max(100).optional(),
    iconUrl: z.string().url().nullable().optional(),
  })
  .refine((data) => Object.keys(data).length > 0, { message: 'No fields to update' });
export type UpdateServerInput = z.infer<typeof updateServerSchema>;

// 'category' groups channels; text/voice are the leaf channels users create.
export const channelTypeSchema = z.enum(['text', 'voice', 'category']);

export const createChannelSchema = z.object({
  name: z.string().min(1).max(100),
  type: channelTypeSchema.default('text'),
  topic: z.string().max(1024).optional(),
  parentCategoryId: z.string().optional(),
});
export type CreateChannelInput = z.infer<typeof createChannelSchema>;

export const updateChannelSchema = z
  .object({
    name: z.string().min(1).max(100).optional(),
    topic: z.string().max(1024).nullable().optional(),
    position: z.number().int().min(0).optional(),
    // null detaches the channel from its category.
    parentCategoryId: z.string().nullable().optional(),
  })
  .refine((data) => Object.keys(data).length > 0, { message: 'No fields to update' });
export type UpdateChannelInput = z.infer<typeof updateChannelSchema>;

// ── Nicknames ───────────────────────────────────────────
export const updateNicknameSchema = z.object({
  // null / empty clears the nickname back to the display name.
  nickname: z.string().max(32).nullable(),
});
export type UpdateNicknameInput = z.infer<typeof updateNicknameSchema>;

// ── Channel permission overwrites ───────────────────────
export const overwriteTypeSchema = z.enum(['role', 'member']);

export const upsertOverwriteSchema = z.object({
  type: overwriteTypeSchema,
  /** Decimal-string permission bitfields granted / revoked on this channel. */
  allow: z.string().regex(/^\d+$/).default('0'),
  deny: z.string().regex(/^\d+$/).default('0'),
});
export type UpsertOverwriteInput = z.infer<typeof upsertOverwriteSchema>;

// ── Messages ────────────────────────────────────────────
export const sendMessageSchema = z.object({
  content: z.string().max(4000),
  replyToId: z.string().optional(),
  attachmentIds: z.array(z.string()).max(10).optional(),
  /** Subset of attachmentIds to hide behind a spoiler cover. */
  spoilerAttachmentIds: z.array(z.string()).max(10).optional(),
});
export type SendMessageInput = z.infer<typeof sendMessageSchema>;

export const editMessageSchema = z.object({
  content: z.string().min(1).max(4000),
});
export type EditMessageInput = z.infer<typeof editMessageSchema>;

export const messageHistoryQuerySchema = z.object({
  /** Fetch messages before this message id (cursor). */
  before: z.string().optional(),
  limit: z.coerce.number().int().min(1).max(100).default(50),
});
export type MessageHistoryQuery = z.infer<typeof messageHistoryQuerySchema>;

// ── Reactions ───────────────────────────────────────────
export const reactionSchema = z.object({
  messageId: z.string(),
  emoji: z.string().min(1).max(64),
});
export type ReactionInput = z.infer<typeof reactionSchema>;

// ── Roles ───────────────────────────────────────────────
export const createRoleSchema = z.object({
  name: z.string().min(1).max(100),
  color: z.number().int().min(0).max(0xffffff).optional(),
  /** Decimal-string-encoded permission bitfield. */
  permissions: z.string().regex(/^\d+$/).optional(),
});
export type CreateRoleInput = z.infer<typeof createRoleSchema>;

export const updateRoleSchema = z.object({
  name: z.string().min(1).max(100).optional(),
  color: z.number().int().min(0).max(0xffffff).optional(),
  permissions: z.string().regex(/^\d+$/).optional(),
  position: z.number().int().min(1).optional(),
});
export type UpdateRoleInput = z.infer<typeof updateRoleSchema>;

export const assignRoleSchema = z.object({
  userId: z.string(),
  roleId: z.string(),
});
export type AssignRoleInput = z.infer<typeof assignRoleSchema>;

export const banMemberSchema = z.object({
  reason: z.string().max(512).optional(),
});
export type BanMemberInput = z.infer<typeof banMemberSchema>;

// ── Invites ─────────────────────────────────────────────
export const createInviteSchema = z.object({
  expiresInSeconds: z.number().int().positive().optional(),
  maxUses: z.number().int().positive().optional(),
});
export type CreateInviteInput = z.infer<typeof createInviteSchema>;

// ── DMs ─────────────────────────────────────────────────
export const createDmSchema = z.object({
  /** Two users → dm; more → group_dm. */
  userIds: z.array(z.string()).min(1).max(9),
});
export type CreateDmInput = z.infer<typeof createDmSchema>;
