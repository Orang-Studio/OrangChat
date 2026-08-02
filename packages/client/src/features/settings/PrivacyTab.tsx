import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import type { DmPrivacy, FriendRequestPrivacy, UpdateProfileInput } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { cn } from "../../lib/cn";
import { desktop, type GameOverride } from "../../lib/desktop";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { updateProfile } from "../auth/api";
import { ConfirmIdentityDialog } from "../e2ee/ConfirmIdentityDialog";
import { HowEncryptionWorksLink } from "../e2ee/HowEncryptionWorks";
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

function GameOverrides({ enabled }: { enabled: boolean }) {
  const listProcesses = desktop?.listGameProcesses;
  const getOverrides = desktop?.getGameOverrides;
  const setOverrides = desktop?.setGameOverrides;
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [processes, setProcesses] = useState<string[]>([]);
  const [overrides, setLocalOverrides] = useState<GameOverride[]>([]);
  const [selectedProcess, setSelectedProcess] = useState("");
  const [gameName, setGameName] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!getOverrides) return;
    let cancelled = false;
    void getOverrides()
      .then((saved) => {
        if (!cancelled) setLocalOverrides(saved);
      })
      .catch(() => {
        if (!cancelled) setError("Could not load your allowed games.");
      });
    return () => {
      cancelled = true;
    };
  }, [getOverrides]);

  if (!listProcesses || !getOverrides || !setOverrides) {
    return (
      <p className="text-xs text-ink-muted">
        Custom game detection is available in the desktop app.
      </p>
    );
  }

  const openPicker = async () => {
    setExpanded(true);
    setError(null);
    if (!enabled) return;
    setLoading(true);
    try {
      const running = await listProcesses();
      setProcesses(running);
      setSelectedProcess((current) => (running.includes(current) ? current : (running[0] ?? "")));
    } catch {
      setError("Could not read the running process list.");
    } finally {
      setLoading(false);
    }
  };

  const save = async (next: GameOverride[]) => {
    setError(null);
    try {
      setLocalOverrides(await setOverrides(next));
    } catch {
      setError("Could not save your allowed games.");
    }
  };

  const addOverride = async () => {
    const name = gameName.trim();
    if (!selectedProcess || !name) return;
    const next = overrides.filter((item) => item.process !== selectedProcess);
    await save([...next, { process: selectedProcess, name }]);
    setGameName("");
  };

  return (
    <div className="space-y-3 rounded-lg border border-border p-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-sm font-medium">Game not detected?</p>
          <p className="text-xs text-ink-muted">
            Allow a running process and choose the game name shown to others.
          </p>
        </div>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={!enabled}
          loading={loading}
          onClick={() => void openPicker()}
        >
          {expanded ? "Refresh" : "Choose"}
        </Button>
      </div>

      {!enabled && (
        <p className="text-xs text-ink-muted">
          Turn activity sharing on to inspect running processes.
        </p>
      )}

      {expanded && enabled && (
        <div className="space-y-3 border-t border-border pt-3">
          {processes.length > 0 ? (
            <>
              <label className="block text-sm font-medium text-ink-secondary">
                Running process
                <select
                  value={selectedProcess}
                  onChange={(event) => setSelectedProcess(event.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm"
                >
                  {processes.map((processName) => (
                    <option key={processName} value={processName}>
                      {processName}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-sm font-medium text-ink-secondary">
                Game name
                <input
                  value={gameName}
                  maxLength={128}
                  placeholder="What should friends see?"
                  onChange={(event) => setGameName(event.target.value)}
                  className="mt-1.5 h-10 w-full rounded-lg border border-border bg-surface-1 px-3 text-sm text-ink placeholder:text-ink-muted"
                />
              </label>
              <Button
                type="button"
                size="sm"
                disabled={!selectedProcess || !gameName.trim()}
                onClick={() => void addOverride()}
              >
                Add game
              </Button>
            </>
          ) : (
            !loading && <p className="text-xs text-ink-muted">No running processes were found.</p>
          )}
        </div>
      )}

      {overrides.length > 0 && (
        <div className="space-y-2 border-t border-border pt-3">
          {overrides.map((override) => (
            <div key={override.process} className="flex items-center justify-between gap-3 text-sm">
              <span className="min-w-0">
                <span className="block truncate font-medium">{override.name}</span>
                <span className="block truncate text-xs text-ink-muted">{override.process}</span>
              </span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() =>
                  void save(overrides.filter((item) => item.process !== override.process))
                }
              >
                Remove
              </Button>
            </div>
          ))}
        </div>
      )}

      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
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
        <SectionTitle>Encryption</SectionTitle>
        <p className="text-sm leading-relaxed text-ink-secondary">
          Every direct message is encrypted, always, and that part cannot be switched off. This
          setting is about how carefully the other person's lock is checked before your messages
          are sent to it.
        </p>
        <Toggle
          checked={user.e2eeStrict}
          // Turning it on is free; turning it off is the direction that costs
          // something, so a session alone must not be able to do it (§6.5).
          onChange={(e2eeStrict) =>
            e2eeStrict ? mutation.mutate({ e2eeStrict: true }) : setConfirmingOff(true)
          }
          label="Check people before messaging them"
          hint="With someone new, your messages wait on this device - locked, sent nowhere - until you have seen their code in person or read the numbers to each other on a call. Worth turning on only if you can realistically meet or ring the people you message."
        />
        <p className="text-xs leading-relaxed text-ink-muted">
          Leaving it off is not "unprotected". Every lock is still checked against a logbook that
          can only be added to, which your own devices read on every start. The difference is
          whether a swapped lock is stopped before it can be used, or caught right after. Group
          conversations always send straight away, either way.
        </p>
        <HowEncryptionWorksLink />
        <ConfirmIdentityDialog
          open={confirmingOff}
          onOpenChange={setConfirmingOff}
          onConfirmed={() => mutation.mutate({ e2eeStrict: false })}
          title="Stop requiring verification"
          explanation="Turning this off lowers the bar for every new conversation, so it takes more than an open session."
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>Activity</SectionTitle>
        <Toggle
          checked={user.gameActivity}
          onChange={(gameActivity) => mutation.mutate({ gameActivity })}
          label="Display the game you're playing"
          hint="The desktop app checks running process names and shares a match with your friends. This is off by default."
        />
        <GameOverrides enabled={user.gameActivity} />
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

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>Friend notifications</SectionTitle>
        <Toggle
          checked={user.notifyFriendRequests}
          onChange={(notifyFriendRequests) => mutation.mutate({ notifyFriendRequests })}
          label="Friend requests"
          hint="Notify me when someone sends me a friend request."
        />
        <Toggle
          checked={user.notifyFriendAccepted}
          onChange={(notifyFriendAccepted) => mutation.mutate({ notifyFriendAccepted })}
          label="Requests accepted"
          hint="Notify me when someone accepts my friend request."
        />
        <Toggle
          checked={user.notifyFriendOnline}
          onChange={(notifyFriendOnline) => mutation.mutate({ notifyFriendOnline })}
          label="Friends coming online"
          hint="Off by default - this fires for every friend each time they open the app."
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
