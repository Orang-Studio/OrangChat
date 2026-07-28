import { useAuthStore } from '../../stores/auth';
import { enrol, markDeviceSeenSafely, selfMonitor } from './identity';
import { loadIdentity } from './keystore';

let lastUserId: string | null = null;

/**
 * Gives a signed-in account an encryption identity if it has none, and audits
 * its own device log every time it starts up.
 *
 * Self-monitoring is the part that matters: if the server ever mints a device
 * or a whole identity in this account's name, this is where the account's own
 * devices see something they never created. Catching it here is what lets a
 * conversation be protected without asking the other person to do anything.
 *
 * Enrolment only ever succeeds when the account has no active device, so
 * opening OrangChat in a second browser does not quietly become a second
 * device - that needs the transfer flow and a TOTP code.
 */
async function sync(userId: string): Promise<void> {
  const identity = await loadIdentity();
  if (!identity || identity.userId !== userId) {
    try {
      await enrol(userId);
      return;
    } catch {
      // An account that already has devices is the normal case here: this
      // browser simply is not one of them yet, and the Encryption settings tab
      // is where that gets resolved.
    }
  }
  await selfMonitor(userId);
  const current = await loadIdentity();
  if (current) await markDeviceSeenSafely(current.deviceId);
}

/** Runs the identity sync whenever the signed-in account changes. */
export function registerE2eeBootstrap(): void {
  const run = (userId: string | undefined) => {
    if (!userId || userId === lastUserId) return;
    lastUserId = userId;
    void sync(userId).catch(() => {
      // Failures surface in Settings → Encryption and on the conversation
      // itself; a startup path is the wrong place to interrupt anyone.
    });
  };

  run(useAuthStore.getState().user?.id);
  useAuthStore.subscribe((state) => run(state.user?.id));
}
