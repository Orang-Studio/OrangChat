import { useParams } from "react-router-dom";
import { LogoMark } from "../../components/LogoMark";
import { InviteCard } from "./InviteCard";

/**
 * Landing page for `/invite/:code`, the far end of a shared invite link.
 *
 * Public on purpose — it is the first thing an invited stranger sees, and
 * hiding it behind the login wall would mean asking someone to make an account
 * before telling them what for. The card handles the sign-in detour itself.
 */
export function InvitePage() {
  const { code } = useParams<{ code: string }>();

  return (
    <main className="flex min-h-dvh flex-col items-center justify-center gap-6 bg-surface-0 p-6">
      <LogoMark className="size-12" />
      {code ? (
        <InviteCard code={code} className="bg-surface-2" />
      ) : (
        <p className="text-sm text-ink-secondary">That invite link is missing a code.</p>
      )}
    </main>
  );
}
