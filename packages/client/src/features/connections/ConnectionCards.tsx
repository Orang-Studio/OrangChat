import { BadgeCheck } from "lucide-react";
import type { Connection } from "@orangchat/shared";
import { PROVIDER_LABEL, ProviderIcon } from "./icons";

/** Host of a link, for the subtitle on custom (unverified) entries. */
function hostOf(url: string | null) {
  if (!url) return null;
  try {
    return new URL(url).host.replace(/^www\./, "");
  } catch {
    return null;
  }
}

function ConnectionCard({ connection }: { connection: Connection }) {
  const { provider, name, profileUrl, verified } = connection;
  const subtitle = provider === "custom" ? hostOf(profileUrl) : PROVIDER_LABEL[provider];

  const body = (
    <>
      <ProviderIcon
        provider={provider}
        className="oc-pf-connection-icon size-5 shrink-0 text-ink-secondary"
      />
      <span className="oc-pf-connection-text min-w-0">
        <span className="flex items-center gap-1">
          <span className="oc-pf-connection-name truncate text-sm font-medium">{name}</span>
          {verified && (
            // The check is the only signal separating an OAuth-proven account
            // from a link someone typed in, so it needs a real label.
            <BadgeCheck
              aria-label="Verified"
              className="oc-pf-connection-verified size-3.5 shrink-0 text-primary"
            />
          )}
        </span>
        {subtitle && (
          <span className="oc-pf-connection-sub block truncate text-xs text-ink-muted">
            {subtitle}
          </span>
        )}
      </span>
    </>
  );

  const className =
    `oc-pf-connection oc-pf-connection-${provider} flex items-center gap-2.5 rounded-md border border-border bg-surface-2 px-2.5 py-2`;

  if (!profileUrl) {
    return (
      <div className={className} data-provider={provider}>
        {body}
      </div>
    );
  }
  return (
    <a
      href={profileUrl}
      data-provider={provider}
      target="_blank"
      // noreferrer as well as noopener: a profile is user-controlled content,
      // and the target site shouldn't get our URL as a referrer.
      rel="noopener noreferrer nofollow"
      className={`${className} transition-colors hover:border-border-strong`}
    >
      {body}
    </a>
  );
}

/** The Connections block of a profile card. Renders nothing when empty. */
export function ConnectionCards({ connections }: { connections: Connection[] }) {
  if (connections.length === 0) return null;
  return (
    <div className="oc-pf-connections oc-pf-section mt-2.5 border-t border-border pt-2.5">
      <h3 className="oc-pf-heading mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-muted">
        Connections
      </h3>
      <div className="oc-pf-connections-grid grid grid-cols-2 gap-1.5">
        {connections.map((c) => (
          <ConnectionCard key={c.id} connection={c} />
        ))}
      </div>
    </div>
  );
}
