import { useState } from "react";
import { Lock } from "lucide-react";
import type { User } from "@orangchat/shared";
import { Avatar, STATUS_LABEL } from "../../components/Avatar";
import { primaryDevice } from "../../components/DeviceIndicators";
import { StatusIcon } from "../../components/StatusIcon";
import { HowEncryptionWorksLink } from "../e2ee/HowEncryptionWorks";
import { GroupIcon } from "./GroupIcon";
import { ProfileDialog } from "../profile/ProfileDialog";
import { t, tCount, tNodes } from "../../lib/i18n";

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
        {t("dmIntro.messagesHereAreLockedOnYour")}
      </p>
      <HowEncryptionWorksLink className="ml-[1.125rem]" />
    </div>
  );
}

export function DmIntro({
  participants,
  groupName,
  groupIconUrl,
}: {
  /** Everyone but the viewer. Empty only in a note-to-self DM. */
  participants: User[];
  /** Set for group DMs; 1:1 DMs take their heading from the other person. */
  groupName?: string | null;
  /** The group's own picture, when it has been given one. */
  groupIconUrl?: string | null;
}) {
  const [profileOpen, setProfileOpen] = useState(false);
  const other = participants[0];
  const isGroup = participants.length > 1 || !!groupName;

  if (!other) {
    return (
      <div className="px-4 pb-2 pt-6">
        <h2 className="text-xl font-bold">{t("dmIntro.justYouHere")}</h2>
        <p className="text-sm text-ink-secondary">
          {t("dmIntro.messagesYouSendInThisConversation")}
        </p>
      </div>
    );
  }

  if (isGroup) {
    return (
      <div className="px-4 pb-2 pt-6">
        {/* Once a group has a picture, that is the group - the stack of member
            avatars is the stand-in for not having one. */}
        {groupIconUrl ? (
          <GroupIcon iconUrl={groupIconUrl} name={groupName ?? undefined} className="mb-3 size-20" />
        ) : (
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
        )}
        <h2 className="text-2xl font-bold">{groupName || "Group conversation"}</h2>
        <p className="text-sm text-ink-secondary">
          {tCount("dmIntro.groupBeginning", participants.length)}
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
        <StatusIcon
          status={other.status}
          mobile={primaryDevice(other.devices) === "mobile"}
          label={null}
          className="size-3"
        />
        {STATUS_LABEL[other.status]}
      </p>
      <p className="mt-2 text-sm text-ink-secondary">
        {tNodes("dmIntro.dmBeginning", {
          name: <span className="font-semibold text-ink">{other.displayName}</span>,
        })}
      </p>
      <EncryptionLine />
      <ProfileDialog user={other} open={profileOpen} onOpenChange={setProfileOpen} />
    </div>
  );
}
