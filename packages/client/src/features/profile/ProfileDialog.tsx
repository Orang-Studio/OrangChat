import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { MessageSquare, UserMinus, UserPlus } from "lucide-react";
import type { User } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { Dialog, DialogContent } from "../../components/ui/Dialog";
import { useAuthStore } from "../../stores/auth";
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

interface ProfileDialogProps {
  user: User;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Discord-style profile popup: the shared ProfileCard plus relationship
 * actions (message / add / remove / accept friend). */
export function ProfileDialog({ user, open, onOpenChange }: ProfileDialogProps) {
  const selfId = useAuthStore((s) => s.user?.id);
  const client = useQueryClient();
  const navigate = useNavigate();
  const { data: friends } = useFriends();
  const { data: requests } = useFriendRequests();

  const isSelf = user.id === selfId;
  const isFriend = friends?.some((f) => f.user.id === user.id) ?? false;
  const outgoing = requests?.outgoing.some((r) => r.user.id === user.id) ?? false;

  // Connections aren't on the User DTO - they'd ride along on every message
  // author. Fetch them only while a profile is actually open.
  const { data: connections } = useQuery({
    queryKey: ["connections", "user", user.id],
    queryFn: () => getUserConnections(user.id),
    enabled: open,
    staleTime: 5 * 60_000,
  });

  const messageMutation = useMutation({
    mutationFn: () => createDm([user.id]),
    onSuccess: (conversation) => {
      upsertConversation(client, conversation);
      onOpenChange(false);
      navigate(`/dms/${conversation.id}`);
    },
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
  });

  const removeMutation = useMutation({
    mutationFn: () => removeFriend(user.id),
    onSuccess: () => removeFriendFromCache(client, user.id),
  });

  // If an inbound request exists, "Add" auto-accepts it server-side.
  const incoming = requests?.incoming.find((r) => r.user.id === user.id);
  const acceptMutation = useMutation({
    mutationFn: () => acceptFriendRequest(incoming!.id),
    onSuccess: (friend) => {
      addFriend(client, friend);
      removeRequestByUser(client, user.id);
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={`${user.displayName}'s profile`}>
        <ProfileCard
          data={{
            displayName: user.displayName,
            username: user.username,
            avatarUrl: user.avatarUrl,
            bannerUrl: user.bannerUrl,
            accentColor: user.accentColor,
            pronouns: user.pronouns,
            bio: user.bio,
            status: user.status,
            devices: user.devices,
            activities: user.activities,
            createdAt: user.createdAt,
            badges: user.badges,
            profileCss: user.profileCss,
            connections,
          }}
        />

        <div>
          {!isSelf && (
            <div className="mt-4 flex gap-2">
              <Button
                variant="secondary"
                className="flex-1"
                loading={messageMutation.isPending}
                onClick={() => messageMutation.mutate()}
              >
                <MessageSquare aria-hidden className="size-4" />
                Message
              </Button>
              {isFriend ? (
                <Button
                  variant="ghost"
                  className="text-danger hover:text-danger"
                  loading={removeMutation.isPending}
                  onClick={() => removeMutation.mutate()}
                >
                  <UserMinus aria-hidden className="size-4" />
                  Remove
                </Button>
              ) : incoming ? (
                <Button
                  loading={acceptMutation.isPending}
                  onClick={() => acceptMutation.mutate()}
                >
                  <UserPlus aria-hidden className="size-4" />
                  Accept
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
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
