import {
  siGithub,
  siGitlab,
  siReddit,
  siSpotify,
  siSteam,
  siTwitch,
  siX,
  siYoutube,
} from "simple-icons";
import { Globe } from "lucide-react";
import type { ConnectionProvider } from "@orangchat/shared";
import { cn } from "../../lib/cn";

/** Brand path data (CC0, from simple-icons). Keyed by our registry keys. */
const BRAND: Partial<Record<ConnectionProvider, { title: string; path: string }>> = {
  github: siGithub,
  gitlab: siGitlab,
  spotify: siSpotify,
  twitch: siTwitch,
  youtube: siYoutube,
  reddit: siReddit,
  x: siX,
  steam: siSteam,
};

export const PROVIDER_LABEL: Record<ConnectionProvider, string> = {
  github: "GitHub",
  gitlab: "GitLab",
  spotify: "Spotify",
  twitch: "Twitch",
  youtube: "YouTube",
  reddit: "Reddit",
  x: "X",
  steam: "Steam",
  custom: "Website",
};

/**
 * Brand mark for a provider, drawn in `currentColor` rather than the brand hex.
 * Several of these marks are near-black (GitHub, X, Steam), so brand colors go
 * invisible on the dark theme; inheriting the text color keeps every icon legible
 * in both themes and matches how the rest of the app draws icons.
 */
export function ProviderIcon({
  provider,
  className,
}: {
  provider: ConnectionProvider;
  className?: string;
}) {
  const brand = BRAND[provider];
  if (!brand) return <Globe aria-hidden className={cn("size-4", className)} />;
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
