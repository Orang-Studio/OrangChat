import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import type { InviteStatus } from "@orangchat/shared";
import { getInvitePreview, joinViaInvite } from "./api";
import { serverKeys } from "./queries";

export const inviteKeys = {
  preview: (code: string) => ["invite", code] as const,
};


export function inviteBlockedReason(status: InviteStatus): string | null {
  switch (status) {
    case "expired":
      return "This invite has expired.";
    case "exhausted":
      return "This invite has reached its use limit.";
    case "banned":
      return "You are banned from this server.";
    default:
      return null;
  }
}


export function useInvite(code: string) {
  const client = useQueryClient();
  const navigate = useNavigate();

  const preview = useQuery({
    queryKey: inviteKeys.preview(code),
    queryFn: ({ signal }) => getInvitePreview(code, signal),
    staleTime: 30_000,
    retry: false,
  });

  const join = useMutation({
    mutationFn: () => joinViaInvite(code),
    onSuccess: (server) => {
      client.invalidateQueries({ queryKey: serverKeys.list });
      client.invalidateQueries({ queryKey: inviteKeys.preview(code) });
      navigate(`/servers/${server.id}`);
    },
  });

  return { preview, join };
}
