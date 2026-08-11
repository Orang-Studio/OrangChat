import { useAuthStore } from '../../stores/auth';
import { enrol, markDeviceSeenSafely, selfMonitor } from './identity';
import { loadIdentity } from './keystore';

let lastUserId: string | null = null;


async function sync(userId: string): Promise<void> {
  const identity = await loadIdentity();
  if (!identity || identity.userId !== userId) {
    try {
      await enrol(userId);
      return;
    } catch {
    }
  }
  await selfMonitor(userId);
  const current = await loadIdentity();
  if (current) await markDeviceSeenSafely(current.deviceId);
}


export function registerE2eeBootstrap(): void {
  const run = (userId: string | undefined) => {
    if (!userId || userId === lastUserId) return;
    lastUserId = userId;
    void sync(userId).catch(() => {
    });
  };

  run(useAuthStore.getState().user?.id);
  useAuthStore.subscribe((state) => run(state.user?.id));
}
