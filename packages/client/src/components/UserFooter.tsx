import { useState } from 'react';
import { Settings } from 'lucide-react';
import { useAuthStore } from '../stores/auth';
import { UserSettingsDialog } from '../features/settings/UserSettingsDialog';
import { Avatar } from './Avatar';
import { DeviceIndicators } from './DeviceIndicators';
import { ActivityStatus } from './ActivityStatus';
import { clientDevice } from '../features/auth/session';
import { t } from "../lib/i18n";


const returningFromLink = () => new URLSearchParams(window.location.search).has('connection');


const returningFromKeyErasure = () => new URLSearchParams(window.location.search).has('keyErasure');


export function UserFooter() {
  const user = useAuthStore((s) => s.user);
  const [settingsOpen, setSettingsOpen] = useState(
    () => returningFromLink() || returningFromKeyErasure(),
  );

  if (!user) return null;

  return (
    <div className="flex items-center gap-2 border-t border-border bg-surface-0/40 p-2">
      <Avatar user={user} className="size-8" />
      <div className="min-w-0 flex-1 leading-tight">
        <p className="truncate text-sm font-semibold">{user.displayName}</p>
        <div className="flex items-center gap-1.5 text-xs text-ink-muted">
          <p className="truncate">@{user.username}</p>
          <DeviceIndicators status="online" devices={[clientDevice]} />
        </div>
        <ActivityStatus activities={user.activities} />
      </div>
      <button
        type="button"
        onClick={() => setSettingsOpen(true)}
        aria-label={t("userFooter.userSettings")}
        title={t("userFooter.userSettings")}
        className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-2 hover:text-ink"
      >
        <Settings aria-hidden className="size-4" />
      </button>
      <UserSettingsDialog
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
        initialSection={
          returningFromKeyErasure() ? 'encryption' : returningFromLink() ? 'connections' : undefined
        }
      />
    </div>
  );
}
