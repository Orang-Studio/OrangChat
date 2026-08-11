import {
  dehydrate,
  hydrate,
  type DehydratedState,
  type QueryClient,
  type QueryKey,
} from "@tanstack/react-query";
import type { SelfUser } from "@orangchat/shared";
import {
  clearOfflineSnapshot,
  getOfflineSnapshot,
  openLocal,
  putOfflineSnapshot,
  sealLocal,
} from "../features/e2ee/keystore";
import { useUnreadStore } from "../stores/unread";

type StoredUnreads = Record<
  string,
  { unread: boolean; unreadCount: number; mentionCount: number; serverId: string | null }
>;

interface StoredOfflineState {
  version: 1;
  user: SelfUser;
  queries: DehydratedState;
  unreads?: StoredUnreads;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const WRITE_DEBOUNCE_MS = 350;
const MAX_MESSAGE_PAGES = 5;
const PERSISTED_ROOTS = new Set(["servers", "server", "dms", "messages", "friends"]);

let client: QueryClient | null = null;
let currentUser: SelfUser | null = null;
let writeTimer: ReturnType<typeof setTimeout> | undefined;


export function shouldPersistQuery(queryKey: QueryKey): boolean {
  return typeof queryKey[0] === "string" && PERSISTED_ROOTS.has(queryKey[0]);
}

export function initOfflineQueryCache(queryClient: QueryClient): void {
  client = queryClient;
  queryClient.getQueryCache().subscribe((event) => {
    if (!currentUser || !shouldPersistQuery(event.query.queryKey)) return;
    scheduleWrite();
  });
  useUnreadStore.subscribe(() => {
    if (currentUser) scheduleWrite();
  });
}


export async function activateOfflineQueryCache(user: SelfUser): Promise<void> {
  currentUser = user;
  const stored = await readSnapshot();
  if (stored?.user.id === user.id && client) {
    hydrate(client, stored.queries);
    useUnreadStore.setState({ channels: stored.unreads ?? {} });
    void client.invalidateQueries({ predicate: (query) => shouldPersistQuery(query.queryKey) });
  }
  scheduleWrite();
}


export async function restoreOfflineSession(): Promise<SelfUser | null> {
  const stored = await readSnapshot();
  if (!stored || !client) return null;
  currentUser = stored.user;
  hydrate(client, stored.queries);
  useUnreadStore.setState({ channels: stored.unreads ?? {} });
  return stored.user;
}

export function clearOfflineQueryCache(): void {
  currentUser = null;
  clearTimeout(writeTimer);
  writeTimer = undefined;
  client?.clear();
  useUnreadStore.setState({ channels: {} });
  void clearOfflineSnapshot().catch(() => {});
}

function scheduleWrite(): void {
  clearTimeout(writeTimer);
  writeTimer = setTimeout(() => void writeSnapshot(), WRITE_DEBOUNCE_MS);
}

async function writeSnapshot(): Promise<void> {
  writeTimer = undefined;
  const user = currentUser;
  const queryClient = client;
  if (!user || !queryClient) return;

  const queries = compactMessageHistory(
    dehydrate(queryClient, {
      shouldDehydrateQuery: (query) =>
        query.state.status === "success" && shouldPersistQuery(query.queryKey),
    }),
  );
  const body: StoredOfflineState = {
    version: 1,
    user,
    queries,
    unreads: useUnreadStore.getState().channels,
  };
  try {
    const sealed = await sealLocal(encoder.encode(JSON.stringify(body)));
    await putOfflineSnapshot({ id: "app", userId: user.id, ...sealed });
  } catch {
  }
}

async function readSnapshot(): Promise<StoredOfflineState | null> {
  try {
    const record = await getOfflineSnapshot();
    if (!record) return null;
    const parsed = JSON.parse(decoder.decode(await openLocal(record))) as StoredOfflineState;
    return parsed.version === 1 && parsed.user?.id === record.userId ? parsed : null;
  } catch {
    await clearOfflineSnapshot().catch(() => {});
    return null;
  }
}


export function compactMessageHistory(state: DehydratedState): DehydratedState {
  return {
    ...state,
    queries: state.queries.map((query) => {
      if (query.queryKey[0] !== "messages") return query;
      const data = query.state.data as
        | { pages?: unknown[]; pageParams?: unknown[] }
        | undefined;
      if (!data?.pages || data.pages.length <= MAX_MESSAGE_PAGES) return query;
      return {
        ...query,
        state: {
          ...query.state,
          data: {
            ...data,
            pages: data.pages.slice(0, MAX_MESSAGE_PAGES),
            pageParams: data.pageParams?.slice(0, MAX_MESSAGE_PAGES),
          },
        },
      };
    }),
  };
}
