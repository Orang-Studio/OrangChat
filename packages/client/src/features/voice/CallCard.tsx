import { Phone, PhoneMissed, Video } from 'lucide-react';
import { formatCallDuration, type CallNotice, type Message, type User } from '@orangchat/shared';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../../components/ui/DropdownMenu';
import { Avatar } from '../../components/Avatar';
import { formatFullTime, formatMessageTime } from '../../lib/time';
import { callActions, useCallStore } from './callStore';
import { t } from "../../lib/i18n";

interface CallCardProps {
  message: Message;
  notice: CallNotice;

  channel: { id: string; name: string | null; serverId: string | null };
  selfId: string | undefined;

  profiles?: Record<string, User>;
}


export function CallCard({ message, notice, channel, selfId, profiles }: CallCardProps) {
  const incoming = useCallStore((s) => s.incoming);
  const current = useCallStore((s) => s.current);
  const live = notice.endedAt === null;
  const onThisCall = current?.channelId === channel.id;
  const ringingAtUs = incoming?.channelId === channel.id;

  const faces = notice.joined
    .map((id) => profiles?.[id])
    .filter((user): user is User => !!user)
    .slice(0, 5);

  const caller = notice.callerId === selfId ? 'You' : message.author.displayName;
  const CallIcon = notice.missed ? PhoneMissed : notice.video ? Video : Phone;

  const title = live
    ? notice.joined.length > 1
      ? 'Ongoing call'
      : `${caller} is calling`
    : notice.missed
      ? notice.callerId === selfId
        ? 'No answer'
        : 'Missed call'
      : `${caller} started a ${notice.video ? 'video call' : 'call'}`;

  const detail = live
    ? notice.ringing.length > 0
      ? 'Ringing...'
      : `${notice.joined.length} on the call`
    : notice.durationSec !== null
      ? `${formatMessageTime(message.createdAt)} · ${formatCallDuration(notice.durationSec)}`
      : formatMessageTime(message.createdAt);

  // Joining is what a live card is for. Answering a call that is ringing at us
  // goes through `accept` rather than `start` so the ringtone stops and the
  // caller is told - `start` would put us in the room with the phone still
  // ringing at both ends.
  const join = () => {
    if (ringingAtUs) void callActions.accept({ video: notice.video });
    else void callActions.start(channel, { video: notice.video });
  };

  const body = (
    <span className="flex min-w-0 flex-1 items-center gap-3">
      <span
        className={
          notice.missed
            ? 'flex size-8 shrink-0 items-center justify-center rounded-full bg-danger/15 text-danger'
            : live
              ? 'flex size-8 shrink-0 items-center justify-center rounded-full bg-success/15 text-success'
              : 'flex size-8 shrink-0 items-center justify-center rounded-full bg-surface-4 text-ink-muted'
        }
      >
        <CallIcon aria-hidden className="size-4" />
      </span>
      <span className="min-w-0 flex-1 text-left">
        <span
          className={`block truncate text-sm font-medium ${notice.missed ? 'text-danger' : 'text-ink'}`}
        >
          {title}
        </span>
        <span className="block truncate text-xs text-ink-muted">{detail}</span>
      </span>
      {faces.length > 0 && (
        <span className="flex shrink-0 -space-x-2">
          {faces.map((user) => (
            <span key={user.id} title={user.displayName}>
              <Avatar user={user} className="size-6 ring-2 ring-surface-2" />
            </span>
          ))}
        </span>
      )}
    </span>
  );

  const shell =
    'mx-auto my-2 flex w-full max-w-md items-center gap-3 rounded-xl border border-border bg-surface-3/70 px-3 py-2 text-left transition-colors hover:bg-surface-4';

  return (
    <div className="px-4" role="status" title={formatFullTime(message.createdAt)}>
      {live ? (
        <button type="button" onClick={join} disabled={onThisCall} className={`${shell} w-full`}>
          {body}
          <span className="shrink-0 rounded-lg bg-success px-2.5 py-1 text-xs font-semibold text-white">
            {onThisCall ? 'On call' : 'Join'}
          </span>
        </button>
      ) : (
        // A finished call is not something to rejoin - there is nothing there.
        // Tapping it offers to start a new one, which is what anyone reaching
        // for an old call actually wants.
        <DropdownMenu>
          <DropdownMenuTrigger className={`${shell} w-full`}>{body}</DropdownMenuTrigger>
          <DropdownMenuContent align="center">
            <DropdownMenuItem onSelect={() => void callActions.start(channel)}>
              <Phone aria-hidden className="size-4" />
              {t("callCard.audioCall")}
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => void callActions.start(channel, { video: true })}>
              <Video aria-hidden className="size-4" />
              {t("callCard.videoCall")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
}
