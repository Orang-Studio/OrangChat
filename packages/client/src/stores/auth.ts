import { create } from "zustand";
import type { SelfUser } from "@orangchat/shared";

export type AuthStatus = "loading" | "authenticated" | "guest";

interface AuthState {
  /** "loading" until the initial refresh-cookie bootstrap settles. */
  status: AuthStatus;
  user: SelfUser | null;
  /** JWT access token, memory-only (never persisted). */
  accessToken: string | null;
}

export const useAuthStore = create<AuthState>(() => ({
  status: "loading",
  user: null,
  accessToken: null,
}));

export const authStoreActions = {
  setSession: (user: SelfUser, accessToken: string) =>
    useAuthStore.setState({ status: "authenticated", user, accessToken }),
  setOfflineSession: (user: SelfUser) =>
    useAuthStore.setState({ status: "authenticated", user, accessToken: null }),
  setUser: (user: SelfUser) => useAuthStore.setState({ user }),
  patchUser: (patch: Partial<SelfUser>) =>
    useAuthStore.setState((s) => (s.user ? { user: { ...s.user, ...patch } } : {})),
  clear: () =>
    useAuthStore.setState({ status: "guest", user: null, accessToken: null }),
};
