import { Menu } from "lucide-react";
import { useConnectionStore } from "../../stores/connection";
import { panelActions } from "../../stores/panels";
import { LogoMark } from "../../components/LogoMark";
import { useServers } from "../servers/queries";
import { t } from "../../lib/i18n";

/** The `/app` home pane inside the shell - greets and points at the rail. */
export function HomePane() {
  const status = useConnectionStore((s) => s.status);
  const { data: servers } = useServers();

  return (
    <div className="relative flex flex-1 flex-col items-center justify-center gap-4 bg-surface-2 p-6 text-center">
      <button
        type="button"
        onClick={panelActions.openLeft}
        aria-label={t("homePane.openNavigation")}
        className="absolute left-2 top-2 rounded-lg p-2 text-ink-secondary transition-colors hover:bg-surface-3 hover:text-ink md:hidden"
      >
        <Menu aria-hidden className="size-5" />
      </button>
      <LogoMark className="size-20 drop-shadow-lg" />
      <h1 className="text-2xl font-bold tracking-tight">
        {t("homePane.orang")}<span className="text-primary">{t("homePane.chat")}</span>
      </h1>
      <p className="max-w-sm text-sm text-ink-secondary">
        {servers && servers.length > 0
          ? "Pick a server on the rail or a conversation on the left to jump back in."
          : "No servers yet - hit the + button on the left to create one or join with an invite code."}
      </p>
      {status !== "connected" && (
        <p className="rounded-lg bg-primary-soft px-3 py-1.5 text-xs text-warning">
          {status === "connecting"
            ? "Connecting to the server…"
            : "Offline - reconnecting…"}
        </p>
      )}
    </div>
  );
}
