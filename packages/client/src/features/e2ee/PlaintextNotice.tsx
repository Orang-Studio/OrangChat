import { Unlock } from 'lucide-react';
import type { User } from '@orangchat/shared';
import { HowEncryptionWorksLink } from './HowEncryptionWorks';
import { useE2eeStore } from './store';
import { t, tCount } from "../../lib/i18n";

/**
 * The banner docs/E2EE.md §10.1 asks for: a conversation that is still plaintext
 * says so, names who it is waiting on, and is never dressed up as "encrypted,
 * pending".
 *
 * It only appears while somebody else has no device that can hold a key. If the
 * only account missing one is the viewer's, ChatView's own blocker banner
 * already says so and two banners saying the same thing is worse than one.
 */
export function PlaintextNotice({ channelId, peers }: { channelId: string; peers: User[] }) {
  const state = useE2eeStore((s) => s.channels[channelId]);
  const latched = useE2eeStore((s) => s.latched[channelId] === true);

  if (!state || latched || state.e2ee || state.capable) return null;

  const withDevices = new Set(state.memberDevices.map((device) => device.userId));
  const waitingOn = peers.filter((peer) => !withDevices.has(peer.id));
  if (waitingOn.length === 0) return null;

  const names = waitingOn.map((peer) => peer.displayName);
  const who =
    names.length === 1
      ? names[0]!
      : names.length === 2
        ? `${names[0]} and ${names[1]}`
        : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`;

  return (
    <div className="mx-4 mb-2 flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2">
      <Unlock aria-hidden className="mt-0.5 size-4 shrink-0 text-warning" />
      <div className="min-w-0 flex-1">
        <p className="text-xs font-medium">{t("plaintextNotice.thisConversationIsNotEncryptedYet")}</p>
        <p className="text-xs leading-relaxed text-ink-secondary">
          {tCount("plaintextNotice.waitingOn", waitingOn.length, { who })}
        </p>
        <HowEncryptionWorksLink className="mt-1" />
      </div>
    </div>
  );
}
