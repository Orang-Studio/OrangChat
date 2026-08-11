import type { ReactNode } from "react";
import { LogoMark } from "../../components/LogoMark";
import { t } from "../../lib/i18n";

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
          {t("authLayout.orang")}<span className="text-primary">{t("authLayout.chat")}</span>
        </span>
      </div>

      <section className="w-full max-w-sm rounded-2xl border border-border bg-surface-2 p-6 shadow-lg">
        <h1 className="text-xl font-bold">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-ink-secondary">{subtitle}</p>}
        <div className="mt-5">{children}</div>
      </section>

      {footer && <div className="text-sm text-ink-secondary">{footer}</div>}
      <nav aria-label={t("authLayout.legal")} className="flex flex-wrap justify-center gap-x-3 gap-y-1 text-xs text-ink-muted">
        <a href="/terms" className="hover:text-ink">{t("authLayout.terms")}</a>
        <a href="/privacy" className="hover:text-ink">{t("authLayout.privacy")}</a>
        <a href="/cookies" className="hover:text-ink">{t("authLayout.cookies")}</a>
        <a href="/guidelines" className="hover:text-ink">{t("authLayout.guidelines")}</a>
      </nav>
    </main>
  );
}
