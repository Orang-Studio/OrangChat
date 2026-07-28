import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Laptop,
  Lock,
  Monitor,
  QrCode as QrCodeIcon,
  ShieldAlert,
  Smartphone,
} from "lucide-react";
import type { E2eeDevice } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { formatFullTime } from "../../lib/time";
import { useAuthStore } from "../../stores/auth";
import { getMyDevices } from "../e2ee/api";
import { HowEncryptionWorksLink } from "../e2ee/HowEncryptionWorks";
import { enrol, revoke, selfMonitor, verifyList } from "../e2ee/identity";
import { loadIdentity } from "../e2ee/keystore";
import { TransferDialog } from "../e2ee/TransferDialog";
import { SectionTitle } from "./controls";
import { KeyErasureSection } from "./KeyErasureSection";

const ICONS = {
  web: Monitor,
  desktop: Laptop,
  android: Smartphone,
} as const;

function DeviceRow({
  device,
  isThisDevice,
  onRevoke,
  revoking,
  canRevoke,
}: {
  device: E2eeDevice;
  isThisDevice: boolean;
  onRevoke: () => void;
  revoking: boolean;
  canRevoke: boolean;
}) {
  const Icon = ICONS[device.platform] ?? Monitor;
  const revoked = device.revokedAt !== null;

  return (
    <li className="flex items-start gap-3 rounded-lg border border-border px-3 py-2.5">
      <Icon aria-hidden className="mt-0.5 size-5 shrink-0 text-ink-secondary" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">
          {device.name}
          {isThisDevice && <span className="ml-2 text-xs text-primary">This device</span>}
          {revoked && <span className="ml-2 text-xs text-ink-muted">Revoked</span>}
        </p>
        <p className="text-xs text-ink-muted">
          {device.authorizedBy === null
            ? "First device on this account"
            : "Added by another of your devices"}
          {" · "}
          Added {formatFullTime(device.createdAt)}
        </p>
        {!revoked && (
          <p className="text-xs text-ink-muted">Last seen {formatFullTime(device.lastSeenAt)}</p>
        )}
      </div>
      {!revoked && !isThisDevice && canRevoke && (
        <Button size="sm" variant="ghost" onClick={onRevoke} disabled={revoking}>
          Revoke
        </Button>
      )}
    </li>
  );
}

export function EncryptionTab() {
  const queryClient = useQueryClient();
  const userId = useAuthStore((s) => s.user?.id);
  const [error, setError] = useState<string | null>(null);
  const [transferOpen, setTransferOpen] = useState(false);

  const local = useQuery({
    queryKey: ["e2ee", "local"],
    queryFn: () => loadIdentity(),
  });

  const devices = useQuery({
    queryKey: ["e2ee", "devices"],
    queryFn: async () => {
      const list = await getMyDevices();
      if (list.devices.length === 0) return { list, verified: null };
      return { list, verified: await verifyList(list) };
    },
    retry: false,
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ["e2ee"] });
  };

  const enrolMutation = useMutation({
    mutationFn: () => {
      if (!userId) throw new Error("Not signed in");
      return enrol(userId);
    },
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: (e: Error) => setError(e.message),
  });

  const revokeMutation = useMutation({
    mutationFn: (deviceId: string) => revoke(deviceId),
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: (e: Error) => setError(e.message),
  });

  const monitorMutation = useMutation({
    mutationFn: () => {
      if (!userId) throw new Error("Not signed in");
      return selfMonitor(userId);
    },
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: (e: Error) => setError(e.message),
  });

  const identity = local.data ?? null;
  const list = devices.data?.list;
  const verifyFailure = devices.error instanceof Error ? devices.error.message : null;
  // An account with devices cannot mint a second genesis - §6 allows exactly
  // one - so this browser's only way in is being signed in by one of them.
  const hasOtherDevices = (list?.devices ?? []).some((d) => d.revokedAt === null);

  return (
    <div className="space-y-6">
      <section className="space-y-2">
        <SectionTitle>End-to-end encryption</SectionTitle>
        <p className="text-sm leading-relaxed text-ink-secondary">
          Your direct messages are locked on your own devices before they are sent. The key is made
          here and cannot be copied off this device, so nobody running OrangChat - and nobody who
          gets hold of our database - can read them.
        </p>
        <HowEncryptionWorksLink />
      </section>

      {(error || verifyFailure) && (
        <div className="flex items-start gap-3 rounded-lg border border-danger bg-danger-soft px-3 py-2.5">
          <ShieldAlert aria-hidden className="mt-0.5 size-5 shrink-0 text-danger" />
          <p className="text-sm">{error ?? verifyFailure}</p>
        </div>
      )}

      {!identity && (
        <section className="rounded-lg border border-border px-3 py-3">
          <div className="flex items-start gap-3">
            <Lock aria-hidden className="mt-0.5 size-5 shrink-0 text-ink-secondary" />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium">This device has no encryption identity yet</p>
              <p className="text-xs text-ink-muted">
                {hasOtherDevices
                  ? "Your account already has devices. This one has to be added from one of them, in person - keys are never copied over the internet."
                  : "Set one up to send and read encrypted messages here. If this is your only device, losing it means losing the messages only it can read."}
              </p>
            </div>
          </div>
          {hasOtherDevices ? (
            <Button className="mt-3" size="sm" onClick={() => setTransferOpen(true)}>
              <QrCodeIcon aria-hidden className="size-4" />
              Add this device
            </Button>
          ) : (
            <Button
              className="mt-3"
              size="sm"
              onClick={() => enrolMutation.mutate()}
              disabled={enrolMutation.isPending}
            >
              {enrolMutation.isPending ? "Setting up…" : "Set up this device"}
            </Button>
          )}
        </section>
      )}

      {identity && (
        <section className="rounded-lg border border-border px-3 py-3">
          <div className="flex items-start gap-3">
            <Smartphone aria-hidden className="mt-0.5 size-5 shrink-0 text-ink-secondary" />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium">Add another device</p>
              <p className="text-xs text-ink-muted">
                Show a one-time code on this PC, then use OrangChat’s scanner on the phone. The
                phone makes its own protected identity; this device authorizes it and sends the
                encrypted history keys after you compare six digits and enter 2FA. The system
                Camera app remains a fallback.
              </p>
            </div>
          </div>
          <Button
            className="mt-3"
            size="sm"
            variant="secondary"
            onClick={() => setTransferOpen(true)}
          >
            <QrCodeIcon aria-hidden className="size-4" />
            Show code for my phone
          </Button>
        </section>
      )}

      {transferOpen && (
        <TransferDialog
          open
          onOpenChange={(next) => {
            setTransferOpen(next);
            if (!next) invalidate();
          }}
          role={identity ? "old" : "new"}
        />
      )}

      <section>
        <SectionTitle>Devices with your keys</SectionTitle>
        {devices.isLoading && <p className="text-sm text-ink-muted">Loading…</p>}
        {list && list.devices.length === 0 && (
          <p className="text-sm text-ink-muted">No devices are enrolled on this account.</p>
        )}
        {list && list.devices.length > 0 && (
          <ul className="space-y-2">
            {list.devices.map((device) => (
              <DeviceRow
                key={device.id}
                device={device}
                isThisDevice={device.id === identity?.deviceId}
                canRevoke={identity !== null}
                revoking={revokeMutation.isPending}
                onRevoke={() => revokeMutation.mutate(device.id)}
              />
            ))}
          </ul>
        )}
      </section>

      <KeyErasureSection stuck={hasOtherDevices && !identity} />

      <section>
        <SectionTitle>The logbook</SectionTitle>
        <p className="text-sm leading-relaxed text-ink-secondary">
          Every device added or removed is written into a logbook that can only be added to, never
          edited or erased. Your devices read it on every start, so a device you did not add cannot
          appear here quietly - you get told, and the entry stays in the book.
        </p>
        {list?.head && (
          <p className="mt-2 font-mono text-xs text-ink-muted">
            {list.head.seq + 1} {list.head.seq === 0 ? "entry" : "entries"} · head{" "}
            {list.head.entryHash.slice(0, 16)}…
          </p>
        )}
        <Button
          className="mt-3"
          size="sm"
          variant="secondary"
          onClick={() => monitorMutation.mutate()}
          disabled={monitorMutation.isPending}
        >
          {monitorMutation.isPending ? "Checking…" : "Check the logbook now"}
        </Button>
      </section>
    </div>
  );
}
