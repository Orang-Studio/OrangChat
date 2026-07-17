import type { ServerMember } from "@orangchat/shared";
import { useAuthStore } from "../../stores/auth";
import { useTypingUserIds } from "./typing";

interface TypingIndicatorProps {
  channelId: string;
  members: ServerMember[];
}

export function TypingIndicator({ channelId, members }: TypingIndicatorProps) {
  const selfId = useAuthStore((s) => s.user?.id);
  const userIds = useTypingUserIds(channelId, selfId);

  if (userIds.length === 0) {
    return <div aria-hidden className="h-5" />;
  }

  const names = userIds.map((id) => {
    const member = members.find((m) => m.userId === id);
    return member ? (member.nickname ?? member.user.displayName) : "Someone";
  });

  const text =
    names.length === 1
      ? `${names[0]} is typing…`
      : names.length <= 3
        ? `${names.slice(0, -1).join(", ")} and ${names.at(-1)} are typing…`
        : "Several people are typing…";

  return (
    <p aria-live="polite" className="h-5 truncate px-4 text-xs text-ink-secondary">
      <span className="font-semibold">{text}</span>
    </p>
  );
}
