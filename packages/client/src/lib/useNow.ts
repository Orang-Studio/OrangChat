import { useSyncExternalStore } from "react";

const listeners = new Set<() => void>();
let now = Date.now();
let timer: ReturnType<typeof setInterval> | undefined;

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (!timer) {
    now = Date.now();
    timer = setInterval(() => {
      now = Date.now();
      listeners.forEach((notify) => notify());
    }, 1_000);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      clearInterval(timer);
      timer = undefined;
    }
  };
}

const snapshot = () => now;


export function useNow(): number {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}

const minuteListeners = new Set<() => void>();
let minuteNow = Date.now();
let minuteTimer: ReturnType<typeof setInterval> | undefined;

function subscribeMinute(listener: () => void): () => void {
  minuteListeners.add(listener);
  if (!minuteTimer) {
    minuteNow = Date.now();
    minuteTimer = setInterval(() => {
      minuteNow = Date.now();
      minuteListeners.forEach((notify) => notify());
    }, 60_000);
  }
  return () => {
    minuteListeners.delete(listener);
    if (minuteListeners.size === 0) {
      clearInterval(minuteTimer);
      minuteTimer = undefined;
    }
  };
}

const minuteSnapshot = () => minuteNow;

/** Same as `useNow`, but ticks once a minute instead of once a second. */
export function useMinuteTick(): number {
  return useSyncExternalStore(subscribeMinute, minuteSnapshot, minuteSnapshot);
}
