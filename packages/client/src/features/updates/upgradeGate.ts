import { create } from "zustand";

import { api } from "../../lib/api";
import { desktop } from "../../lib/desktop";


export type UpdateSeverity = "none" | "optional" | "recommended" | "required";

export interface UpdatePolicy {
  severity: UpdateSeverity;
  latest?: string | null;
  minSupported?: string | null;
}

interface UpgradeGateState {
  severity: UpdateSeverity;
  latest: string | null;

  dismissed: string[];
  setPolicy: (policy: UpdatePolicy) => void;

  requireUpgrade: (latest?: string) => void;
  dismiss: () => void;
}

const DISMISSED_KEY = "oc-update-dismissed";

function loadDismissed(): string[] {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === "string") : [];
  } catch {
    return [];
  }
}

export const useUpgradeGate = create<UpgradeGateState>((set, get) => ({
  severity: "none",
  latest: null,
  dismissed: loadDismissed(),

  setPolicy: (policy) =>
    set({ severity: policy.severity, latest: policy.latest ?? get().latest }),

  requireUpgrade: (latest) =>
    set((s) => ({ severity: "required", latest: latest ?? s.latest })),

  dismiss: () => {
    const { latest, dismissed } = get();
    if (!latest || dismissed.includes(latest)) return;
    const next = [...dismissed, latest].slice(-10);
    try {
      localStorage.setItem(DISMISSED_KEY, JSON.stringify(next));
    } catch {
    }
    set({ dismissed: next });
  },
}));


export function reportUpgradeRequired(latest?: string): void {
  useUpgradeGate.getState().requireUpgrade(latest);
}


export async function fetchUpdatePolicy(): Promise<void> {
  if (!desktop?.version) return;
  const query = new URLSearchParams({ platform: "desktop", version: desktop.version });
  try {
    const policy = await api<UpdatePolicy>(`/updates/policy?${query.toString()}`);
    useUpgradeGate.getState().setPolicy(policy);
  } catch {
    // A failed check must never itself block the app - if the build really is
    // retired, the 426 on any other request raises the wall anyway.
  }
}

/** Whether a dismissible prompt should currently be shown. */
export function shouldPrompt(state: UpgradeGateState): boolean {
  if (state.severity !== "optional" && state.severity !== "recommended") return false;
  return !!state.latest && !state.dismissed.includes(state.latest);
}
