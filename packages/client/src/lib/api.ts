import { useAuthStore } from "../stores/auth";
import { refreshSession } from "../features/auth/session";
import { clientVersionHeaders } from "../features/updates/clientVersion";
import { reportUpgradeRequired } from "../features/updates/upgradeGate";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;

  readonly latest: string | undefined;

  constructor(status: number, message: string, code?: string, latest?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.latest = latest;
  }
}

interface ApiOptions {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";

  json?: unknown;
  signal?: AbortSignal;
}


export function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  return request<T>(path, options, true);
}

async function request<T>(
  path: string,
  { method = "GET", json, signal }: ApiOptions,
  allowRefresh: boolean,
): Promise<T> {
  const token = useAuthStore.getState().accessToken;
  const headers: Record<string, string> = { ...clientVersionHeaders() };
  if (json !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(`/api${path}`, {
    method,
    headers,
    credentials: "include",
    body: json !== undefined ? JSON.stringify(json) : undefined,
    signal,
  });

  if (res.status === 401 && allowRefresh && !path.startsWith("/auth/")) {
    if (await refreshSession()) {
      return request<T>(path, { method, json, signal }, false);
    }
  }

  if (!res.ok) {
    const error = await toApiError(res);
    // 426 means the server has stopped serving this build entirely, so every
    // other request is about to fail the same way. Raise it once, globally,
    // rather than letting each caller render its own broken-looking failure.
    if (error.status === 426) reportUpgradeRequired(error.latest);
    throw error;
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

async function toApiError(res: Response): Promise<ApiError> {
  let message = res.statusText || `Request failed (${res.status})`;
  let code: string | undefined;
  let latest: string | undefined;
  try {
    const body = (await res.json()) as {
      message?: string;
      error?: string;
      code?: string;
      latest?: string;
    };
    message = body.message ?? body.error ?? message;
    code = body.code;
    latest = body.latest ?? undefined;
  } catch {
    // Non-JSON error body; keep the fallback message.
  }
  return new ApiError(res.status, message, code, latest);
}
