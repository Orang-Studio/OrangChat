import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { MessageSquare, UserMinus, UserPlus } from "lucide-react";
import type { User } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { Dialog, DialogCardContent } from "../../components/ui/Dialog";
import { useAuthStore } from "../../stores/auth";
import { usePresenceStore } from "../../stores/presence";
import { toast } from "../../stores/toasts";
import { errorMessage } from "../../lib/errors";
import { getUserConnections } from "../connections/api";
import { createDm } from "../dms/api";
import { upsertConversation } from "../dms/queries";
import {
  acceptFriendRequest,
  removeFriend,
  sendFriendRequest,
} from "../friends/api";
import {
  addFriend,
  removeFriendFromCache,
  removeRequestByUser,
  useFriendRequests,
  useFriends,
} from "../friends/queries";
import { ProfileCard } from "./ProfileCard";
import { useWidgetCatalogMap } from "./widgetCatalog";
import { t } from "../../lib/i18n";

interface ProfileDialogProps {
  user: User;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}


export function ProfileDialog({ user, open, onOpenChange }: ProfileDialogProps) {
  const selfId = useAuthStore((s) => s.user?.id);
  const presence = usePresenceStore((s) => s[user.id]);
  const client = useQueryClient();
  const navigate = useNavigate();
  const { data: friends } = useFriends();
  const { data: requests } = useFriendRequests();

  const isSelf = user.id === selfId;
  const isFriend = friends?.some((f) => f.user.id === user.id) ?? false;
  const outgoing = requests?.outgoing.some((r) => r.user.id === user.id) ?? false;

  const { data: connections } = useQuery({
    queryKey: ["connections", "user", user.id],
    queryFn: () => getUserConnections(user.id),
    enabled: open,
    staleTime: 5 * 60_000,
  });

  const widgetCatalog = useWidgetCatalogMap();

  const messageMutation = useMutation({
    mutationFn: () => createDm([user.id]),
    onSuccess: (conversation) => {
      upsertConversation(client, conversation);
      onOpenChange(false);
      navigate(`/dms/${conversation.id}`);
    },
    onError: (error) =>
      toast.error(errorMessage(error, "Couldn't start a conversation")),
  });

  const addMutation = useMutation({
    mutationFn: () => sendFriendRequest(user.username),
    onSuccess: (result) => {
      if (result.accepted) {
        addFriend(client, result.friend);
        removeRequestByUser(client, user.id);
      } else {
        // Reflect the new outbound request without a refetch.
        void client.invalidateQueries({ queryKey: ["friends", "requests"] });
      }
    },
    onError: (error) => toast.error(errorMessage(error, "Couldn't send the friend request")),
  });

  const removeMutation = useMutation({
    mutationFn: () => removeFriend(user.id),
    onSuccess: () => removeFriendFromCache(client, user.id),
    onError: (error) => toast.error(errorMessage(error, "Couldn't remove friend")),
  });

  // If an inbound request exists, "Add" auto-accepts it server-side.
  const incoming = requests?.incoming.find((r) => r.user.id === user.id);
  const acceptMutation = useMutation({
    mutationFn: () => acceptFriendRequest(incoming!.id),
    onSuccess: (friend) => {
      addFriend(client, friend);
      removeRequestByUser(client, user.id);
    },
    onError: (error) => toast.error(errorMessage(error, "Couldn't accept the request")),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogCardContent title={`${user.displayName}'s profile`}>
        <ProfileCard
          data={{
            displayName: user.displayName,
            username: user.username,
            avatarUrl: user.avatarUrl,
            bannerUrl: user.bannerUrl,
            accentColor: user.accentColor,
            pronouns: user.pronouns,
            bio: user.bio,
            status: presence?.status ?? user.status,
            devices: presence?.devices ?? user.devices,
            activities: presence?.activities ?? user.activities,
            createdAt: user.createdAt,
            badges: user.badges,
            profileCss: user.profileCss,
            profileWidgets: user.profileWidgets,
            profileFields: user.profileFields,
            widgetCatalog,
            connections,
          }}
          actions={
            !isSelf && (
              <>
                <Button
                  variant="secondary"
                  className="flex-1"
                  loading={messageMutation.isPending}
                  onClick={() => messageMutation.mutate()}
                >
                  <MessageSquare aria-hidden className="size-4" />
                  {t("profileDialog.message")}
                </Button>
                {isFriend ? (
                  <Button
                    variant="ghost"
                    className="text-danger hover:text-danger"
                    loading={removeMutation.isPending}
                    onClick={() => removeMutation.mutate()}
                  >
                    <UserMinus aria-hidden className="size-4" />
                    {t("common.remove")}
                  </Button>
                ) : incoming ? (
                  <Button
                    loading={acceptMutation.isPending}
                    onClick={() => acceptMutation.mutate()}
                  >
                    <UserPlus aria-hidden className="size-4" />
                    {t("profileDialog.accept")}
                  </Button>
                ) : (
                  <Button
                    disabled={outgoing}
                    loading={addMutation.isPending}
                    onClick={() => addMutation.mutate()}
                  >
                    <UserPlus aria-hidden className="size-4" />
                    {outgoing ? "Request sent" : "Add friend"}
                  </Button>
                )}
              </>
            )
          }
        />
      </DialogCardContent>
    </Dialog>
  );
}
