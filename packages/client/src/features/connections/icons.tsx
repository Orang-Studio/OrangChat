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
