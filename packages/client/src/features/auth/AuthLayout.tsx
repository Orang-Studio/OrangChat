import type { ReactNode } from "react";
import { LogoMark } from "../../components/LogoMark";

interface AuthLayoutProps {
  title: string;
  subtitle?: string;
  children: ReactNode;
  footer?: ReactNode;
}

export function AuthLayout({ title, subtitle, children, footer }: AuthLayoutProps) {
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center gap-6 p-6">
      <div className="flex items-center gap-3">
        <LogoMark className="size-12" />
        <span className="text-2xl font-bold tracking-tight">
          Orang<span className="text-primary">Chat</span>
        </span>
      </div>

      <section className="w-full max-w-sm rounded-2xl border border-border bg-surface-2 p-6 shadow-lg">
        <h1 className="text-xl font-bold">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-ink-secondary">{subtitle}</p>}
        <div className="mt-5">{children}</div>
      </section>

      {footer && <div className="text-sm text-ink-secondary">{footer}</div>}
      <nav aria-label="Legal" className="flex flex-wrap justify-center gap-x-3 gap-y-1 text-xs text-ink-muted">
        <a href="/terms" className="hover:text-ink">Terms</a>
        <a href="/privacy" className="hover:text-ink">Privacy</a>
        <a href="/cookies" className="hover:text-ink">Cookies</a>
        <a href="/guidelines" className="hover:text-ink">Guidelines</a>
      </nav>
    </main>
  );
}
