import { Link } from "react-router-dom";
import type { InvitePreview } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { Button } from "../../components/ui/Button";
import { useAuthStore } from "../../stores/auth";
import { ServerIcon } from "./ServerIcon";
import { setPendingInvite } from "./invite-url";
import { inviteBlockedReason, useInvite } from "./useInvite";

/** "12 members", but "1 member". */
function memberLabel(count: number): string {
  return `${count.toLocaleString()} ${count === 1 ? "member" : "members"}`;
}

function Frame({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        "w-full max-w-md rounded-xl border border-border bg-surface-1 p-3 shadow-sm",
        className,
      )}
    >
      {children}
    </div>
  );
}

/**
 * An invite link, resolved into the server it leads to and a button to act on
 * it. Rendered inline under a message that contains an invite URL, and as the
 * body of the /invite/:code landing page.
 */
export function InviteCard({ code, className }: { code: string; className?: string }) {
  const { preview, join } = useInvite(code);
  const signedIn = useAuthStore((s) => s.status === "authenticated");

  if (preview.isPending) {
    return (
      <Frame className={className}>
        <p className="text-sm text-ink-muted">Resolving invite…</p>
      </Frame>
    );
  }

  // A dead code is a dead link, not an error worth a red banner - the invite may
  // simply have been revoked since it was posted.
  if (preview.isError || !preview.data) {
    return (
      <Frame className={className}>
        <p className="text-xs font-medium uppercase tracking-wide text-ink-muted">
          Invite invalid
        </p>
        <p className="mt-1 text-sm text-ink-secondary">
          This invite is expired, revoked, or never existed.
        </p>
      </Frame>
    );
  }

  return (
    <Frame className={className}>
      <InviteBody
        data={preview.data}
        signedIn={signedIn}
        code={code}
        joining={join.isPending}
        joinError={join.error?.message ?? null}
        onJoin={() => join.mutate()}
      />
    </Frame>
  );
}

interface InviteBodyProps {
  data: InvitePreview;
  signedIn: boolean;
  code: string;
  joining: boolean;
  joinError: string | null;
  onJoin: () => void;
}

function InviteBody({ data, signedIn, code, joining, joinError, onJoin }: InviteBodyProps) {
  const blocked = inviteBlockedReason(data.status);
  const isMember = data.status === "alreadyMember";

  return (
    <>
      <p className="mb-2 text-xs font-medium uppercase tracking-wide text-ink-muted">
        {data.inviterName
          ? `${data.inviterName} invited you to join`
          : "You've been invited to join"}
      </p>
      <div className="flex items-center gap-3">
        <ServerIcon
          name={data.server.name}
          iconUrl={data.server.iconUrl}
          className="size-12 shrink-0"
          fallbackClassName="bg-surface-3 text-ink-secondary"
        />
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-ink">{data.server.name}</p>
          <p className="text-xs text-ink-muted">{memberLabel(data.memberCount)}</p>
        </div>

        {/* Signed out, the button is the funnel: bounce through login and come
            straight back to this invite rather than dumping them on the home
            page to find it again. */}
        {!signedIn ? (
          <Button asChild size="sm">
            <Link
              to="/login"
              state={{ from: { pathname: `/invite/${code}` } }}
              onClick={() => setPendingInvite(code)}
            >
              Sign in to join
            </Link>
          </Button>
        ) : isMember ? (
          <Button asChild variant="secondary" size="sm">
            <Link to={`/servers/${data.server.id}`}>Joined</Link>
          </Button>
        ) : (
          <Button size="sm" onClick={onJoin} loading={joining} disabled={!!blocked}>
            Join
          </Button>
        )}
      </div>

      {(blocked || joinError) && (
        <p role="alert" className="mt-2 text-xs text-danger">
          {joinError ?? blocked}
        </p>
      )}
    </>
  );
}
