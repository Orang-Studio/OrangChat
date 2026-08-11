import type { IconType } from "react-icons";
import { FcGoogle } from "react-icons/fc";
import { FaDiscord } from "react-icons/fa";
import type { OAuthProvider } from "@orangchat/shared";


const PROVIDERS: { id: OAuthProvider; label: string; Icon: IconType; iconClass?: string }[] = [
  { id: "google", label: "Continue with Google", Icon: FcGoogle },
  { id: "discord", label: "Continue with Discord", Icon: FaDiscord, iconClass: "text-[#5865F2]" },
];

export function OAuthButtons() {
  return (
    <div className="space-y-2">
      {PROVIDERS.map(({ id, label, Icon, iconClass }) => (
        <a
          key={id}
          href={`/api/auth/oauth/${id}`}
          className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-lg border border-border-strong bg-surface-3 text-sm text-ink transition-colors hover:bg-surface-4"
        >
          <Icon aria-hidden className={`size-4 ${iconClass ?? ""}`} />
          {label}
        </a>
      ))}
    </div>
  );
}

export function OAuthDivider() {
  return (
    <div aria-hidden className="my-5 flex items-center gap-3">
      <span className="h-px flex-1 bg-border" />
      <span className="text-xs uppercase tracking-wide text-ink-muted">or</span>
      <span className="h-px flex-1 bg-border" />
    </div>
  );
}
