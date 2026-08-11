import { ExternalLink } from "lucide-react";
import { LogoMark } from "../../components/LogoMark";
import { SectionTitle } from "./controls";
import { t } from "../../lib/i18n";

const APP_VERSION = typeof __APP_VERSION__ === "string" ? __APP_VERSION__ : "dev";
const BUILD_TIME = typeof __BUILD_TIME__ === "string" ? __BUILD_TIME__ : null;

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between border-b border-border py-2 text-sm last:border-b-0">
      <span className="text-ink-secondary">{label}</span>
      <span className="font-mono text-ink">{value}</span>
    </div>
  );
}

export function AboutTab() {
  const buildLabel = BUILD_TIME ? new Date(BUILD_TIME).toLocaleString() : "-";
  const ua = navigator.userAgent;
  const platform =
    /android/i.test(ua) ? "Android"
    : /iphone|ipad/i.test(ua) ? "iOS"
    : /mac/i.test(ua) ? "macOS"
    : /win/i.test(ua) ? "Windows"
    : /linux/i.test(ua) ? "Linux"
    : "Web";

  return (
    <div className="space-y-6">
      <div className="flex flex-col items-center gap-3 py-4 text-center">
        <LogoMark className="size-16" />
        <div>
          <h2 className="text-xl font-semibold">{t("aboutTab.orangchat")}</h2>
          <p className="text-sm text-ink-muted">{t("aboutTab.versionNumber", { version: APP_VERSION })}</p>
        </div>
        <p className="max-w-xs text-sm text-ink-secondary">
          {t("aboutTab.aFastSelfHostedChatFor")}
        </p>
      </div>

      <div>
        <SectionTitle>{t("aboutTab.build")}</SectionTitle>
        <div className="rounded-lg border border-border bg-surface-1 px-3">
          <Row label={t("aboutTab.version")} value={APP_VERSION} />
          <Row label={t("aboutTab.built")} value={buildLabel} />
          <Row label={t("aboutTab.client")} value="Web (PWA)" />
          <Row label={t("aboutTab.platform")} value={platform} />
        </div>
      </div>

      <div>
        <SectionTitle>{t("aboutTab.links")}</SectionTitle>
        <div className="space-y-1">
          <a
            href="https://oranges.lt"
            target="_blank"
            rel="noreferrer"
            className="oc-link flex items-center justify-between rounded-lg border border-border px-3 py-2.5 text-sm transition-colors hover:border-border-strong"
          >
            {t("aboutTab.orangesLt")}
            <ExternalLink aria-hidden className="size-4 text-ink-muted" />
          </a>
          <a
            href="https://chat.oranges.lt"
            target="_blank"
            rel="noreferrer"
            className="oc-link flex items-center justify-between rounded-lg border border-border px-3 py-2.5 text-sm transition-colors hover:border-border-strong"
          >
            chat.oranges.lt
            <ExternalLink aria-hidden className="size-4 text-ink-muted" />
          </a>
        </div>
      </div>

      <p className="text-center text-xs text-ink-muted">
        {t("aboutTab.copyrightMadeWith", { year: new Date().getFullYear() })}
      </p>
    </div>
  );
}
