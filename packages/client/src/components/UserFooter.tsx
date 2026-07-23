import { useState } from "react";
import { Settings } from "lucide-react";
import { useAuthStore } from "../stores/auth";
import { UserSettingsDialog } from "../features/settings/UserSettingsDialog";
import { Avatar } from "./Avatar";
import { DeviceIndicators } from "./DeviceIndicators";
import { ActivityStatus } from "./ActivityStatus";
import { clientDevice } from "../features/auth/session";

/** A connection callback bounces the browser back to `/?connection=…`; reopen
 * settings where the user left off. Read once at mount - ConnectionsTab strips
 * the param after showing the result. */
const returningFromLink = () =>
  new URLSearchParams(window.location.search).has("connection");

/** Bottom-of-sidebar current-user strip with the settings gear. */
export function UserFooter() {
  const user = useAuthStore((s) => s.user);
  const [settingsOpen, setSettingsOpen] = useState(returningFromLink);

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
        aria-label="User settings"
        title="User settings"
        className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-2 hover:text-ink"
      >
        <Settings aria-hidden className="size-4" />
      </button>
      <UserSettingsDialog
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
        initialSection={returningFromLink() ? "connections" : undefined}
      />
    </div>
  );
}
