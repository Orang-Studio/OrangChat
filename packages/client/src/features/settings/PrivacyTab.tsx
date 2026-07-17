import { useMutation } from "@tanstack/react-query";
import type { DmPrivacy, FriendRequestPrivacy, UpdateProfileInput } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { updateProfile } from "../auth/api";
import { SectionTitle, Toggle } from "./controls";

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

  const mutation = useMutation({
    mutationFn: (input: UpdateProfileInput) => updateProfile(input),
    onSuccess: (updated) => authStoreActions.setUser(updated),
  });

  if (!user) return null;

  return (
    <div className="space-y-6">
      <div>
        <SectionTitle>Direct messages</SectionTitle>
        <p className="mb-3 text-sm text-ink-secondary">
          Who can open a new conversation with you. Conversations you're already in stay
          open either way.
        </p>
        <ChoiceList
          value={user.dmPrivacy}
          options={DM_OPTIONS}
          onChange={(dmPrivacy) => mutation.mutate({ dmPrivacy })}
        />
      </div>

      <div className="border-t border-border pt-5">
        <SectionTitle>Friend requests</SectionTitle>
        <p className="mb-3 text-sm text-ink-secondary">Who can send you a friend request.</p>
        <ChoiceList
          value={user.friendRequestPrivacy}
          options={REQUEST_OPTIONS}
          onChange={(friendRequestPrivacy) => mutation.mutate({ friendRequestPrivacy })}
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>What you share</SectionTitle>
        <Toggle
          checked={user.typingIndicators}
          onChange={(typingIndicators) => mutation.mutate({ typingIndicators })}
          label="Send typing indicators"
          hint="Let people see “is typing…” while you write."
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
