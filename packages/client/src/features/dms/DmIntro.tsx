import { useState } from "react";
import { Lock } from "lucide-react";
import type { User } from "@orangchat/shared";
import { Avatar, STATUS_COLOR, STATUS_LABEL } from "../../components/Avatar";
import { cn } from "../../lib/cn";
import { HowEncryptionWorksLink } from "../e2ee/HowEncryptionWorks";
import { ProfileDialog } from "../profile/ProfileDialog";

/**
 * Header shown at the very start of a conversation, once there's no older
 * history left to load - the DM equivalent of a channel's welcome block.
 * Mirrors Discord: a large avatar, who you're talking to, and their presence.
 */
/**
 * The onboarding explainer from docs/E2EE.md §6.6, item 5: one line, stated
 * once, at the place people actually look. Deliberately not a choice - asking
 * someone to pick a security mode before they have sent a message guarantees a
 * random answer - but the full explanation is one tap away for anyone who wants
 * to know what the sentence actually means.
 */
function EncryptionLine() {
  return (
    <div className="mt-2 space-y-1">
      <p className="flex items-start gap-1.5 text-xs text-ink-muted">
        <Lock aria-hidden className="mt-0.5 size-3 shrink-0" />
        Messages here are locked on your device, so only the people in this conversation can read
        them.
      </p>
      <HowEncryptionWorksLink className="ml-[1.125rem]" />
    </div>
  );
}

export function DmIntro({
  participants,
  groupName,
}: {
  /** Everyone but the viewer. Empty only in a note-to-self DM. */
  participants: User[];
  /** Set for group DMs; 1:1 DMs take their heading from the other person. */
  groupName?: string | null;
}) {
  const [profileOpen, setProfileOpen] = useState(false);
  const other = participants[0];
  const isGroup = participants.length > 1 || !!groupName;

  if (!other) {
    return (
      <div className="px-4 pb-2 pt-6">
        <h2 className="text-xl font-bold">Just you here</h2>
        <p className="text-sm text-ink-secondary">
          Messages you send in this conversation are only visible to you.
        </p>
      </div>
    );
  }

  if (isGroup) {
    return (
      <div className="px-4 pb-2 pt-6">
        <div className="mb-3 flex -space-x-2">
          {participants.slice(0, 8).map((user) => (
            <Avatar
              key={user.id}
              user={user}
              status={user.status}
              className="size-12 ring-2 ring-surface-2"
            />
          ))}
        </div>
        <h2 className="text-2xl font-bold">{groupName || "Group conversation"}</h2>
        <p className="text-sm text-ink-secondary">
          This is the beginning of this group, with {participants.length} other{" "}
          {participants.length === 1 ? "person" : "people"}.
        </p>
        <EncryptionLine />
      </div>
    );
  }

  return (
    <div className="px-4 pb-2 pt-6">
      <button
        type="button"
        onClick={() => setProfileOpen(true)}
        className="mb-3 block rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        aria-label={`View ${other.displayName}'s profile`}
      >
        <Avatar user={other} status={other.status} className="size-20" />
      </button>
      <h2 className="text-2xl font-bold">{other.displayName}</h2>
      <p className="text-sm text-ink-secondary">@{other.username}</p>
      <p className="mt-1 flex items-center gap-1.5 text-sm text-ink-muted">
        <span aria-hidden className={cn("size-2 rounded-full", STATUS_COLOR[other.status])} />
        {STATUS_LABEL[other.status]}
      </p>
      <p className="mt-2 text-sm text-ink-secondary">
        This is the beginning of your direct message history with{" "}
        <span className="font-semibold text-ink">{other.displayName}</span>.
      </p>
      <EncryptionLine />
      <ProfileDialog user={other} open={profileOpen} onOpenChange={setProfileOpen} />
    </div>
  );
}
