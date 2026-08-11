import { useParams } from "react-router-dom";
import { LogoMark } from "../../components/LogoMark";
import { InviteCard } from "./InviteCard";
import { t } from "../../lib/i18n";


export function InvitePage() {
  const { code } = useParams<{ code: string }>();

  return (
    <main className="flex min-h-dvh flex-col items-center justify-center gap-6 bg-surface-0 p-6">
      <LogoMark className="size-12" />
      {code ? (
        <InviteCard code={code} className="bg-surface-2" />
      ) : (
        <p className="text-sm text-ink-secondary">{t("invitePage.thatInviteLinkIsMissingA")}</p>
      )}
    </main>
  );
}
