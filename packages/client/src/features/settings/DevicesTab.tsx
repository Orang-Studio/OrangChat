import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Laptop, Monitor, Smartphone } from "lucide-react";
import type { DeviceSession } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { formatFullTime } from "../../lib/time";
import { SectionTitle } from "./controls";
import { getSessions, revokeOtherSessions, revokeSession } from "./api";
import { t, tCount } from "../../lib/i18n";


function describe(userAgent: string | null): { label: string; icon: typeof Monitor } {
  if (!userAgent) return { label: "Unknown device", icon: Monitor };
  const ua = userAgent.toLowerCase();

  if (ua.includes("orangchat-android")) return { label: "OrangChat for Android", icon: Smartphone };
  if (ua.includes("electron")) return { label: "OrangChat desktop app", icon: Laptop };
  if (ua.includes("android")) return { label: "Android browser", icon: Smartphone };
  if (ua.includes("iphone") || ua.includes("ipad")) return { label: "iOS browser", icon: Smartphone };

  const browser = ua.includes("firefox")
    ? "Firefox"
    : ua.includes("edg/")
      ? "Edge"
      : ua.includes("chrome")
        ? "Chrome"
        : ua.includes("safari")
          ? "Safari"
          : "Browser";
  const os = ua.includes("windows")
    ? "Windows"
    : ua.includes("mac os") || ua.includes("macintosh")
      ? "macOS"
      : ua.includes("linux")
        ? "Linux"
        : null;

  return { label: os ? `${browser} on ${os}` : browser, icon: Monitor };
}

function SessionRow({
  session,
  onRevoke,
  revoking,
}: {
  session: DeviceSession;
  onRevoke: () => void;
  revoking: boolean;
}) {
  const { label, icon: Icon } = describe(session.userAgent);

  return (
    <li className="flex items-start gap-3 rounded-lg border border-border px-3 py-2.5">
      <Icon aria-hidden className="mt-0.5 size-5 shrink-0 text-ink-secondary" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">
          {label}
          {session.current && (
            <span className="ml-2 rounded bg-primary-soft px-1.5 py-0.5 text-xs text-primary">
              {t("devicesTab.thisDevice")}
            </span>
          )}
        </p>
        <p className="truncate text-xs text-ink-muted">
          {session.ip ?? "unknown IP"}
          {session.lastSeenAt && ` · last active ${formatFullTime(session.lastSeenAt)}`}
        </p>
        {session.createdAt && (
          <p className="text-xs text-ink-muted">
            {t("devicesTab.signedInAt", { time: formatFullTime(session.createdAt) })}
          </p>
        )}
      </div>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="shrink-0 text-danger hover:text-danger"
        loading={revoking}
        onClick={onRevoke}
      >
        {session.current ? "Sign out" : "Revoke"}
      </Button>
    </li>
  );
}

/**
 * Live sessions, one per signed-in device. A session is a refresh token, so
 * revoking one stops that device renewing - it keeps working until its current
 * access token expires, which is minutes, not indefinitely.
 */
export function DevicesTab() {
  const queryClient = useQueryClient();
  const [pendingId, setPendingId] = useState<string | null>(null);

  const { data, isPending } = useQuery({ queryKey: ["sessions"], queryFn: getSessions });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["sessions"] });
  };

  const revokeOne = useMutation({
    mutationFn: (id: string) => revokeSession(id),
    onSuccess: (_res, id) => {
      setPendingId(null);
      // Revoking your own session ends it; a reload lands on the sign-in screen.
      if (data?.sessions.find((s) => s.id === id)?.current) {
        window.location.reload();
        return;
      }
      refresh();
    },
    onError: () => setPendingId(null),
  });

  const revokeOthers = useMutation({
    mutationFn: revokeOtherSessions,
    onSuccess: refresh,
  });

  const sessions = data?.sessions ?? [];
  const others = sessions.filter((s) => !s.current).length;

  return (
    <div className="space-y-4">
      <div>
        <SectionTitle>{t("devicesTab.whereYoureSignedIn")}</SectionTitle>
        <p className="text-sm text-ink-secondary">
          {t("devicesTab.eachEntryIsADeviceWith")}
        </p>
      </div>

      {isPending ? (
        <div className="h-24 animate-pulse rounded-lg bg-surface-3" />
      ) : (
        <ul className="space-y-2">
          {sessions.map((session) => (
            <SessionRow
              key={session.id}
              session={session}
              revoking={pendingId === session.id && revokeOne.isPending}
              onRevoke={() => {
                setPendingId(session.id);
                revokeOne.mutate(session.id);
              }}
            />
          ))}
        </ul>
      )}

      {revokeOne.isError && <p className="text-sm text-danger">{revokeOne.error.message}</p>}

      {others > 0 && (
        <div className="border-t border-border pt-4">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            loading={revokeOthers.isPending}
            onClick={() => revokeOthers.mutate()}
          >
            {tCount("devicesTab.signOutOtherDevices", others)}
          </Button>
          {revokeOthers.isError && (
            <p className="mt-2 text-sm text-danger">{revokeOthers.error.message}</p>
          )}
        </div>
      )}
    </div>
  );
}
