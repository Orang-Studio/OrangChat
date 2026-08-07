import { create } from "zustand";

export type ToastType = "info" | "success" | "error";

export interface Toast {
  id: number;
  message: string;
  type: ToastType;
}

interface ToastsState {
  toasts: Toast[];
}

export const useToastsStore = create<ToastsState>(() => ({
  toasts: [],
}));

let nextId = 1;

/** A burst - e.g. an outbox flush rejecting several queued rows at once -
 * shouldn't stack the surface past what's readable. */
const MAX_TOASTS = 4;

export const toastActions = {
  show(message: string, type: ToastType): number {
    const existing = useToastsStore.getState().toasts;
    // Repeated identical feedback (the same failure hitting several rows)
    // collapses into the one already on screen instead of stacking.
    const dup = existing.find((t) => t.message === message && t.type === type);
    if (dup) return dup.id;
    const id = nextId++;
    const toasts = [...existing, { id, message, type }];
    useToastsStore.setState({
      toasts: toasts.length > MAX_TOASTS ? toasts.slice(toasts.length - MAX_TOASTS) : toasts,
    });
    return id;
  },
  dismiss(id: number): void {
    useToastsStore.setState((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
  },
};

/** Imperative API for surfacing one-off feedback from anywhere in the app. */
export const toast = {
  info: (message: string) => toastActions.show(message, "info"),
  success: (message: string) => toastActions.show(message, "success"),
  error: (message: string) => toastActions.show(message, "error"),
};
