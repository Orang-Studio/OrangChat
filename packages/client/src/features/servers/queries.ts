import { useQuery } from "@tanstack/react-query";
import { parsePermissions } from "@orangchat/shared";
import { getMyPermissions, getServerDetail, listServers } from "./api";


export const serverKeys = {
  list: ["servers"] as const,
  detail: (serverId: string) => ["server", serverId] as const,
  permissions: (serverId: string) => ["permissions", serverId] as const,
};

export function useServers() {
  return useQuery({ queryKey: serverKeys.list, queryFn: listServers });
}

export function useServerDetail(serverId: string | undefined) {
  return useQuery({
    queryKey: serverKeys.detail(serverId!),
    queryFn: () => getServerDetail(serverId!),
    enabled: !!serverId,
  });
}


export function useMyPermissions(serverId: string | undefined) {
  return useQuery({
    queryKey: serverKeys.permissions(serverId!),
    queryFn: () => getMyPermissions(serverId!),
    enabled: !!serverId,
    select: (data) => parsePermissions(data.permissions),
    staleTime: 60_000,
  });
}
