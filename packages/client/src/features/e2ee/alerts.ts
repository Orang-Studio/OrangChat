import { create } from 'zustand';

export type SecurityAlertKind =
  'identity-changed' | 'log-fork' | 'log-rollback' | 'unauthorized-device' | 'log-omission';

export interface SecurityAlert {
  userId: string;
  kind: SecurityAlertKind;
  /** Who the alert is about, for copy that has to stand on its own. */
  subject: string;
  detail: string;
  at: string;
}

interface AlertState {
  alerts: SecurityAlert[];
}

/**
 * The loud path, and the only loud path. Everything else about encryption is
 * meant to be invisible; these are the events where a server has been caught
 * contradicting something a device already committed to, and §6.4 is explicit
 * that they are full-screen failures rather than a toast someone swipes away.
 */
export const useSecurityAlerts = create<AlertState>(() => ({ alerts: [] }));

export function raiseSecurityAlert(alert: Omit<SecurityAlert, 'at'>): void {
  useSecurityAlerts.setState((prev) => {
    if (prev.alerts.some((a) => a.userId === alert.userId && a.kind === alert.kind)) return prev;
    return { alerts: [...prev.alerts, { ...alert, at: new Date().toISOString() }] };
  });
}

export function dismissSecurityAlert(userId: string, kind: SecurityAlertKind): void {
  useSecurityAlerts.setState((prev) => ({
    alerts: prev.alerts.filter((a) => !(a.userId === userId && a.kind === kind)),
  }));
}

export function clearSecurityAlerts(): void {
  useSecurityAlerts.setState({ alerts: [] });
}
