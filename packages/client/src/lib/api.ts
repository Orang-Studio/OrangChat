import { useAuthStore } from "../stores/auth";
import { refreshSession } from "../features/auth/session";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

interface ApiOptions {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  /** JSON-serialized as the request body. */
  json?: unknown;
  signal?: AbortSignal;
}

/**
 * Typed same-origin API client. Attaches the in-memory Bearer token; on a 401
 * (outside /auth/*) it refreshes the session once and retries the request.
 */
export function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  return request<T>(path, options, true);
}

async function request<T>(
  path: string,
  { method = "GET", json, signal }: ApiOptions,
  allowRefresh: boolean,
): Promise<T> {
  const token = useAuthStore.getState().accessToken;
  const headers: Record<string, string> = {};
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

  if (!res.ok) throw await toApiError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

async function toApiError(res: Response): Promise<ApiError> {
  let message = res.statusText || `Request failed (${res.status})`;
  let code: string | undefined;
  try {
    const body = (await res.json()) as {
      message?: string;
      error?: string;
      code?: string;
    };
    message = body.message ?? body.error ?? message;
    code = body.code;
  } catch {
    // Non-JSON error body; keep the fallback message.
  }
  return new ApiError(res.status, message, code);
}
