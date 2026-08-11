import type { Message } from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { cn } from "../../lib/cn";
import { RichText } from "../../lib/markdown";
import { formatFullTime } from "../../lib/time";
import { ReactionStrip } from "./Reactions";


export interface MediaContext {
  message: Message;

  mentions?: Record<string, string>;

  mentionUsers?: Record<string, { id: string; name: string }>;

  selfId?: string;
}


export function MediaSenderBar({ context, className }: { context: MediaContext; className?: string }) {
  const { message } = context;
  return (
    <div
      className={cn(
        "flex items-start gap-2.5 bg-black/60 px-4 py-3 text-white",
        className,
      )}
      onClick={(event) => event.stopPropagation()}
    >
      <Avatar user={message.author} className="size-9" />
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-baseline gap-x-2">
          <span className="text-sm font-semibold">{message.author.displayName}</span>
          <time dateTime={message.createdAt} className="text-[11px] text-white/60">
            {formatFullTime(message.createdAt)}
          </time>
        </p>
        {message.content.trim() !== "" && (
          <div className="mt-0.5 line-clamp-3 break-words text-sm text-white/85">
            <RichText
              content={message.content}
              mentions={context.mentions}
              mentionUsers={context.mentionUsers}
              selfId={context.selfId}
            />
          </div>
        )}
        <ReactionStrip message={message} tone="dark" alwaysPicker className="mt-2" />
      </div>
    </div>
  );
}
