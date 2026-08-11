import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import type { DmPrivacy, FriendRequestPrivacy, UpdateProfileInput } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { updateProfile } from "../auth/api";
import { ConfirmIdentityDialog } from "../e2ee/ConfirmIdentityDialog";
import { HowEncryptionWorksLink } from "../e2ee/HowEncryptionWorks";
import { SectionTitle, Toggle } from "./controls";
import { t } from "../../lib/i18n";

const DM_OPTIONS: { value: DmPrivacy; label: string; hint: string }[] = [
  { value: "everyone", label: "Everyone", hint: "Anyone can start a conversation with you." },
  { value: "friends", label: "Friends only", hint: "Only people on your friends list." },
  { value: "none", label: "No one", hint: "Nobody new can message you." },
];

const REQUEST_OPTIONS: { value: FriendRequestPrivacy; label: string; hint: string }[] = [
  { value: "everyone", label: "Everyone", hint: "Anyone who knows your username." },
  { value: "mutual", label: "Friends of friends", hint: "Only people you share a friend with." },
  { value: "none", label: "No one", hint: "Nobody can send you requests." },
];

function ChoiceList<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: { value: T; label: string; hint: string }[];
  onChange: (next: T) => void;
}) {
  return (
    <div className="space-y-2">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={value === option.value}
          onClick={() => onChange(option.value)}
          className={cn(
            "flex w-full items-start gap-3 rounded-lg border px-3 py-2.5 text-left transition-colors",
            value === option.value
              ? "border-primary bg-primary-soft"
              : "border-border hover:border-border-strong",
          )}
        >
          <span
            className={cn(
              "mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full border-2",
              value === option.value ? "border-primary" : "border-border-strong",
            )}
          >
            {value === option.value && <span className="size-2 rounded-full bg-primary" />}
          </span>
          <span>
            <span className="block text-sm font-medium">{option.label}</span>
            <span className="block text-xs text-ink-muted">{option.hint}</span>
          </span>
        </button>
      ))}
    </div>
  );
}

export function PrivacyTab() {
  const user = useAuthStore((s) => s.user);
  const [confirmingOff, setConfirmingOff] = useState(false);

  const mutation = useMutation({
    mutationFn: (input: UpdateProfileInput) => updateProfile(input),
    onSuccess: (updated) => authStoreActions.setUser(updated),
  });

  if (!user) return null;

  return (
    <div className="space-y-6">
      <div>
        <SectionTitle>{t("privacyTab.directMessages")}</SectionTitle>
        <p className="mb-3 text-sm text-ink-secondary">
          {t("privacyTab.whoCanOpenANewConversation")}
        </p>
        <ChoiceList
          value={user.dmPrivacy}
          options={DM_OPTIONS}
          onChange={(dmPrivacy) => mutation.mutate({ dmPrivacy })}
        />
      </div>

      <div className="border-t border-border pt-5">
        <SectionTitle>{t("privacyTab.friendRequests")}</SectionTitle>
        <p className="mb-3 text-sm text-ink-secondary">{t("privacyTab.whoCanSendYouAFriend")}</p>
        <ChoiceList
          value={user.friendRequestPrivacy}
          options={REQUEST_OPTIONS}
          onChange={(friendRequestPrivacy) => mutation.mutate({ friendRequestPrivacy })}
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>{t("privacyTab.encryption")}</SectionTitle>
        <p className="text-sm leading-relaxed text-ink-secondary">
          {t("privacyTab.everyDirectMessageIsEncryptedAlways")}
        </p>
        <Toggle
          checked={user.e2eeStrict}
          onChange={(e2eeStrict) =>
            e2eeStrict ? mutation.mutate({ e2eeStrict: true }) : setConfirmingOff(true)
          }
          label={t("privacyTab.checkPeopleBeforeMessagingThem")}
          hint={t("privacyTab.withSomeoneNewYourMessagesWait")}
        />
        <p className="text-xs leading-relaxed text-ink-muted">
          {t("privacyTab.leavingItOffIsNotUnprotected")}
        </p>
        <HowEncryptionWorksLink />
        <ConfirmIdentityDialog
          open={confirmingOff}
          onOpenChange={setConfirmingOff}
          onConfirmed={() => mutation.mutate({ e2eeStrict: false })}
          title={t("privacyTab.stopRequiringVerification")}
          explanation="Turning this off lowers the bar for every new conversation, so it takes more than an open session."
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>{t("privacyTab.whatYouShare")}</SectionTitle>
        <Toggle
          checked={user.typingIndicators}
          onChange={(typingIndicators) => mutation.mutate({ typingIndicators })}
          label={t("privacyTab.sendTypingIndicators")}
          hint={t("privacyTab.letPeopleSeeIsTypingWhile")}
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>{t("privacyTab.friendNotifications")}</SectionTitle>
        <Toggle
          checked={user.notifyFriendRequests}
          onChange={(notifyFriendRequests) => mutation.mutate({ notifyFriendRequests })}
          label={t("privacyTab.friendRequests")}
          hint={t("privacyTab.notifyMeWhenSomeoneSendsMe")}
        />
        <Toggle
          checked={user.notifyFriendAccepted}
          onChange={(notifyFriendAccepted) => mutation.mutate({ notifyFriendAccepted })}
          label={t("privacyTab.requestsAccepted")}
          hint={t("privacyTab.notifyMeWhenSomeoneAcceptsMy")}
        />
        <Toggle
          checked={user.notifyFriendOnline}
          onChange={(notifyFriendOnline) => mutation.mutate({ notifyFriendOnline })}
          label={t("privacyTab.friendsComingOnline")}
          hint={t("privacyTab.offByDefaultThisFiresFor")}
        />
      </div>

      {mutation.isError && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {mutation.error.message}
        </p>
      )}
    </div>
  );
}
