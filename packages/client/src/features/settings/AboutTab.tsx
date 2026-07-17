import { ExternalLink } from "lucide-react";
import { LogoMark } from "../../components/LogoMark";
import { SectionTitle } from "./controls";

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
          <h2 className="text-xl font-semibold">OrangChat</h2>
          <p className="text-sm text-ink-muted">Version {APP_VERSION}</p>
        </div>
        <p className="max-w-xs text-sm text-ink-secondary">
          A fast, self-hosted chat for servers, DMs, and voice - part of the Oranges.LT family.
        </p>
      </div>

      <div>
        <SectionTitle>Build</SectionTitle>
        <div className="rounded-lg border border-border bg-surface-1 px-3">
          <Row label="Version" value={APP_VERSION} />
          <Row label="Built" value={buildLabel} />
          <Row label="Client" value="Web (PWA)" />
          <Row label="Platform" value={platform} />
        </div>
      </div>

      <div>
        <SectionTitle>Links</SectionTitle>
        <div className="space-y-1">
          <a
            href="https://oranges.lt"
            target="_blank"
            rel="noreferrer"
            className="oc-link flex items-center justify-between rounded-lg border border-border px-3 py-2.5 text-sm transition-colors hover:border-border-strong"
          >
            Oranges.LT
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
        © {new Date().getFullYear()} Oranges.LT · Made with 🍊
      </p>
    </div>
  );
}
