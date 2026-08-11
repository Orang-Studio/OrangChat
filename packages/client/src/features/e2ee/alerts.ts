import { create } from 'zustand';

export type SecurityAlertKind =
  'identity-changed' | 'log-fork' | 'log-rollback' | 'unauthorized-device' | 'log-omission';

export interface SecurityAlert {
  userId: string;
  kind: SecurityAlertKind;

  subject: string;
  detail: string;
  at: string;
}

interface AlertState {
  alerts: SecurityAlert[];
}


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
