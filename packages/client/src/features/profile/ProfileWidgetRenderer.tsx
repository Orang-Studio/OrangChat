import type { ReactNode } from "react";
import type { ProfileWidget, ProfileWidgetBlock } from "@orangchat/shared";
import { ActivityStatus } from "../../components/ActivityStatus";
import { formatFullTime } from "../../lib/time";
import { RichText } from "../../lib/markdown";
import { t } from "../../lib/i18n";
import { ConnectionCards } from "../connections/ConnectionCards";
import { ProfileBadges } from "./ProfileBadges";
import type { ProfileCardData } from "./ProfileCard";
import {
  fallbackDefinition,
  isBlank,
  listFrom,
  resolveLayout,
  substitute,
  type PlaceholderSource,
} from "./widgets";

const HEADING_FALLBACK: Record<string, () => string> = {
  bio: () => t("profileCard.aboutMe"),
  "member-since": () => t("profileCard.memberSince"),
};

const SPACER_HEIGHT: Record<string, string> = {
  none: "0",
  sm: "0.5rem",
  md: "1rem",
  lg: "2rem",
};

function sourceFor(data: ProfileCardData, widget: ProfileWidget): PlaceholderSource {
  return {
    username: data.username,
    displayName: data.displayName,
    pronouns: data.pronouns,
    createdAt: data.createdAt,
    fields: data.profileFields,
    config: widget.config ?? null,
  };
}

function heading(
  raw: string | undefined,
  source: PlaceholderSource,
  type: string,
): string | null {
  if (raw) {
    const filled = substitute(raw, source);
    if (!filled.includes("{")) return filled;
  }
  return HEADING_FALLBACK[type]?.() ?? null;
}

function renderNative(component: string, data: ProfileCardData): ReactNode {
  switch (component) {
    case "bio":
      return isBlank(data.bio) ? null : (
        <div className="oc-pf-bio-text text-sm">
          <RichText content={data.bio!} />
        </div>
      );
    case "pronouns":
      return isBlank(data.pronouns) ? null : (
        <p className="oc-pf-pronouns text-xs text-ink-muted">{data.pronouns}</p>
      );
    case "badges":
      return data.badges?.length ? <ProfileBadges badges={data.badges} /> : null;
    case "activity":
      return data.activities?.length ? (
        <ActivityStatus activities={data.activities} className="oc-pf-activity" compact={false} />
      ) : null;
    case "connections":
      return data.connections?.length ? <ConnectionCards connections={data.connections} /> : null;
    case "member-since":
      return data.createdAt ? (
        <p className="oc-pf-member-text text-sm">{formatFullTime(data.createdAt)}</p>
      ) : null;
    default:
      return null;
  }
}

function renderBlock(
  block: ProfileWidgetBlock,
  data: ProfileCardData,
  source: PlaceholderSource,
  type: string,
): ReactNode {
  switch (block.block) {
    case "native":
      return renderNative(block.component, data);

    case "section": {
      const body = renderBlock(block.body, data, source, type);
      if (!body) return null;
      const title = heading(block.heading, source, type);
      return (
        <>
          {title && (
            <h3 className="oc-pf-heading mb-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">
              {title}
            </h3>
          )}
          {body}
        </>
      );
    }

    case "text": {
      const value = substitute(block.value, source);
      if (isBlank(value)) return null;
      return (
        <div className="oc-pf-text text-sm">
          {block.markdown === false ? value : <RichText content={value} />}
        </div>
      );
    }

    case "rows": {
      const items = listFrom(source, block.from);
      if (!items.length) return null;
      return (
        <dl className="oc-pf-rows grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          {items.map((item, i) => (
            <div key={i} className="oc-pf-row contents">
              <dt className="oc-pf-row-label truncate text-ink-muted">
                {substitute(String(item.label ?? ""), source)}
              </dt>
              <dd className="oc-pf-row-value min-w-0 break-words">
                {substitute(String(item.value ?? ""), source)}
              </dd>
            </div>
          ))}
        </dl>
      );
    }

    case "links": {
      const items = listFrom(source, block.from).filter((item) => !isBlank(String(item.url ?? "")));
      if (!items.length) return null;
      return (
        <ul className="oc-pf-links flex flex-col gap-1 text-sm">
          {items.map((item, i) => (
            <li key={i} className="oc-pf-link-item">
              <a
                className="oc-pf-link text-accent hover:underline"
                href={String(item.url)}
                target="_blank"
                rel="noopener noreferrer nofollow"
              >
                {isBlank(String(item.label ?? "")) ? String(item.url) : String(item.label)}
              </a>
            </li>
          ))}
        </ul>
      );
    }

    case "image": {
      const src = substitute(block.src, source);
      if (isBlank(src) || src.includes("{")) return null;
      return (
        <img
          className="oc-pf-image max-h-48 w-full rounded object-cover"
          src={src}
          alt={block.alt ? substitute(block.alt, source) : ""}
        />
      );
    }

    case "spacer": {
      const size = block.size ? substitute(block.size, source) : "md";
      return (
        <div className="oc-pf-spacer" style={{ height: SPACER_HEIGHT[size] ?? SPACER_HEIGHT.md }} />
      );
    }

    case "divider":
      return <hr className="oc-pf-divider border-border" />;

    default:
      return null;
  }
}

export function ProfileWidgets({ data }: { data: ProfileCardData }) {
  const layout = resolveLayout(data.profileWidgets);

  const rendered = layout
    .map((widget) => {
      if (widget.hidden) return null;
      const definition = data.widgetCatalog?.[widget.type] ?? fallbackDefinition(widget.type);
      if (!definition) return null;
      const source = sourceFor(data, widget);
      const body = renderBlock(definition.render, data, source, widget.type);
      if (!body) return null;
      return (
        <div key={widget.id} className="oc-pf-widget" data-widget={widget.type}>
          {body}
        </div>
      );
    })
    .filter(Boolean);

  if (!rendered.length) return null;
  return <div className="oc-pf-widgets flex flex-col gap-2.5">{rendered}</div>;
}
