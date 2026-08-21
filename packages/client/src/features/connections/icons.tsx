import {
  siGithub,
  siGitlab,
  siReddit,
  siSteam,
  siTwitch,
  siX,
  siYoutube,
} from "simple-icons";
import { Globe, Music } from "lucide-react";
import type { ConnectionProvider } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { t } from "../../lib/i18n";


const BRAND: Partial<Record<ConnectionProvider, { title: string; path: string }>> = {
  github: siGithub,
  gitlab: siGitlab,
  twitch: siTwitch,
  youtube: siYoutube,
  reddit: siReddit,
  x: siX,
  steam: siSteam,
};

const BRAND_LABEL: Partial<Record<ConnectionProvider, string>> = {
  github: "GitHub",
  gitlab: "GitLab",
  twitch: "Twitch",
  youtube: "YouTube",
  reddit: "Reddit",
  x: "X",
  steam: "Steam",
};

export function providerLabel(provider: ConnectionProvider): string {
  if (provider === "spotify") return t("connectionCards.music");
  return BRAND_LABEL[provider] ?? t("connectionCards.website");
}


export function ProviderIcon({
  provider,
  className,
}: {
  provider: ConnectionProvider;
  className?: string;
}) {
  const brand = BRAND[provider];
  if (!brand) {
    const Fallback = provider === "spotify" ? Music : Globe;
    return <Fallback aria-hidden className={cn("size-4", className)} />;
  }
  return (
    <svg
      role="img"
      aria-hidden
      viewBox="0 0 24 24"
      fill="currentColor"
      className={cn("size-4", className)}
    >
      <path d={brand.path} />
    </svg>
  );
}
