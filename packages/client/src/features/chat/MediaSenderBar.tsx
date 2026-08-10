import type { Message } from "@orangchat/shared";
import { Avatar } from "../../components/Avatar";
import { cn } from "../../lib/cn";
import { RichText } from "../../lib/markdown";
import { formatFullTime } from "../../lib/time";
import { ReactionStrip } from "./Reactions";

/**
 * Everything a media viewer needs to say who this file came from. Carried as one
 * object because it travels four components deep - message row, attachment list,
 * preview, viewer - and the alternative is the same four props at every hop.
 */
export interface MediaContext {
  message: Message;
  /** userId → display name, for `<@id>` in the caption. */
  mentions?: Record<string, string>;
  /** username → user, for `@username` in the caption. */
  mentionUsers?: Record<string, { id: string; name: string }>;
  /** The viewer's id, so mentions of them highlight. */
  selfId?: string;
}

/**
 * The bar under a full-screen image or video: who sent it, when, whatever they
 * said with it, and the reactions.
 *
 * Opening a file used to lose all of that - the viewer showed the bytes and a
 * filename, and the only way back to the message they belong to was to close it.
 * Reacting from here is what the strip's `alwaysPicker` is for: there is no
 * hover toolbar over a lightbox to reach the picker from.
 */
export function MediaSenderBar({ context, className }: { context: MediaContext; className?: string }) {
  const { message } = context;
  return (
    <div
      className={cn(
        "flex items-start gap-2.5 bg-black/60 px-4 py-3 text-white",
        className,
      )}
      // Clicking the backdrop closes the viewer; the bar is not the backdrop.
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
          // A caption can be a whole essay; three lines is enough to recognise
          // it, and the message itself is still there underneath the viewer.
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
