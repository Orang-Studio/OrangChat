import { Reply } from "lucide-react";
import { DEFAULT_PREFS, FONT_SCALE_MAX, FONT_SCALE_MIN, setPref, usePrefs } from "../../lib/prefs";
import { Avatar } from "../../components/Avatar";
import { Button } from "../../components/ui/Button";
import { cn } from "../../lib/cn";
import { useAuthStore } from "../../stores/auth";
import { SectionTitle, SegmentedControl, Toggle } from "./controls";
import { t, tNodes } from "../../lib/i18n";

const SAMPLE_AUTHOR = { displayName: "Rasa", avatarUrl: null };


function ChatPreview() {
  const me = useAuthStore((s) => s.user);
  const self = {
    displayName: me?.displayName ?? "You",
    avatarUrl: me?.avatarUrl ?? null,
  };

  const Row = ({
    author,
    time,
    lead = true,
    children,
  }: {
    author: { displayName: string; avatarUrl: string | null };
    time: string;
    lead?: boolean;
    children: React.ReactNode;
  }) => (
    <div className={cn("oc-message px-3 py-0.5", lead && "oc-message-lead mt-3")}>
      <div className="flex gap-3">
        {lead ? (
          <Avatar user={author} className="mt-0.5 size-10 shrink-0" />
        ) : (
          <span className="w-10 shrink-0 pt-1 text-right text-[10px] leading-5 text-ink-muted">
            {time.slice(-5)}
          </span>
        )}
        <div className="min-w-0 flex-1">
          {lead && (
            <p className="flex items-baseline gap-2">
              <span className="font-semibold">{author.displayName}</span>
              <span className="text-xs text-ink-muted">{time}</span>
            </p>
          )}
          <div className="break-words text-sm leading-relaxed">{children}</div>
        </div>
      </div>
    </div>
  );

  return (
    <div
      aria-label={t("accessibilityTab.chatPreview")}
      className="overflow-hidden rounded-lg border border-border bg-surface-2 pb-2"
    >
      <div className="flex h-9 items-center gap-2 border-b border-border px-3 text-sm font-semibold">
        <span aria-hidden className="text-ink-muted">
          #
        </span>
        preview
      </div>

      <Row author={SAMPLE_AUTHOR} time="Today at 14:02">
        {t("accessibilityTab.theQuickBrownFoxJumpsOver")}
      </Row>
      <Row author={SAMPLE_AUTHOR} time="Today at 14:02" lead={false}>
        {t("accessibilityTab.thisSecondLineIsGroupedUnder")}
      </Row>

      <div className="oc-message oc-message-lead mt-3 px-3 py-0.5">
        <div className="mb-0.5 flex items-center gap-1.5 pl-[3.25rem] text-xs text-ink-muted">
          <Reply aria-hidden className="size-3.5 -scale-x-100" />
          <span className="font-medium text-ink-secondary">{SAMPLE_AUTHOR.displayName}</span>
          <span className="truncate">{t("accessibilityTab.theQuickBrownFoxJumpsOver")}</span>
        </div>
        <div className="flex gap-3">
          <Avatar user={self} className="mt-0.5 size-10 shrink-0" />
          <div className="min-w-0 flex-1">
            <p className="flex items-baseline gap-2">
              <span className="font-semibold">{self.displayName}</span>
              <span className="text-xs text-ink-muted">{t("accessibilityTab.todayAt1403")}</span>
            </p>
            <div className="break-words text-sm leading-relaxed">
              {tNodes("accessibilityTab.readablePreview", {
                link: (
                  <a
                    href="#preview"
                    onClick={(e) => e.preventDefault()}
                    className="oc-link text-info"
                  >
                    {t("accessibilityTab.aLink")}
                  </a>
                ),
              })}
            </div>
            <div className="mt-1 flex flex-wrap gap-1">
              <span className="flex items-center gap-1 rounded-md border border-primary bg-primary-soft px-2 py-0.5 text-sm">
                👍 <span className="text-xs font-semibold text-ink-secondary">2</span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export function AccessibilityTab() {
  const prefs = usePrefs();

  return (
    <div className="space-y-6">
      <div>
        <SectionTitle>{t("accessibilityTab.textSize")}</SectionTitle>
        <p className="mb-3 text-sm text-ink-secondary">
          {t("accessibilityTab.scalesTheEntireInterfaceTheSample")}
        </p>
        <input
          type="range"
          min={FONT_SCALE_MIN}
          max={FONT_SCALE_MAX}
          step={0.05}
          value={prefs.fontScale}
          onChange={(e) => setPref("fontScale", Number(e.target.value))}
          aria-label={t("accessibilityTab.textSize")}
          className="w-full accent-[var(--oc-primary)]"
        />
        <div className="mt-2 flex items-center justify-between text-xs text-ink-muted">
          <span>{t("accessibilityTab.smaller")}</span>
          <span>{Math.round(prefs.fontScale * 100)}%</span>
          <span>{t("accessibilityTab.larger")}</span>
        </div>
        <div className="mt-3">
          <ChatPreview />
        </div>
      </div>

      <div className="border-t border-border pt-5">
        <SectionTitle>{t("accessibilityTab.messageDensity")}</SectionTitle>
        <p className="mb-3 text-sm text-ink-secondary">
          {t("accessibilityTab.compactTightensTheSpacingBetweenMessages")}
        </p>
        <SegmentedControl
          value={prefs.messageDensity}
          onChange={(v) => setPref("messageDensity", v)}
          options={[
            { value: "cozy", label: "Cozy" },
            { value: "compact", label: "Compact" },
          ]}
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>{t("accessibilityTab.motionContrast")}</SectionTitle>
        <Toggle
          checked={prefs.reducedMotion}
          onChange={(v) => setPref("reducedMotion", v)}
          label={t("accessibilityTab.reduceMotion")}
          hint={t("accessibilityTab.minimizeAnimationsAndTransitionsAcrossThe")}
        />
        <Toggle
          checked={prefs.highContrast}
          onChange={(v) => setPref("highContrast", v)}
          label={t("accessibilityTab.increaseContrast")}
          hint={t("accessibilityTab.strongerBordersAndHigherContrastText")}
        />
        <Toggle
          checked={prefs.underlineLinks}
          onChange={(v) => setPref("underlineLinks", v)}
          label={t("accessibilityTab.underlineLinks")}
          hint={t("accessibilityTab.alwaysUnderlineLinksNotJustOn")}
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>{t("accessibilityTab.chatInput")}</SectionTitle>
        <Toggle
          checked={prefs.sendOnEnter}
          onChange={(v) => setPref("sendOnEnter", v)}
          label={t("accessibilityTab.sendMessagesWithEnter")}
          hint={
            prefs.sendOnEnter
              ? "Enter sends, Shift+Enter adds a line break."
              : "Enter adds a line break, Ctrl/⌘+Enter sends."
          }
        />
      </div>

      {/* The "Brand name" section that toggled `iHateAdas` lived here. It is out
          of the UI for now, not gone: the pref, its reset below, and the rename
          machinery in lib/brandReplacement.ts are all intact and inert behind
          BRAND_REPLACEMENT_ENABLED. Restore the Toggle and flip that flag to
          bring it back. */}

      <div className="border-t border-border pt-5">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => {
            setPref("fontScale", DEFAULT_PREFS.fontScale);
            setPref("messageDensity", DEFAULT_PREFS.messageDensity);
            setPref("reducedMotion", DEFAULT_PREFS.reducedMotion);
            setPref("highContrast", DEFAULT_PREFS.highContrast);
            setPref("underlineLinks", DEFAULT_PREFS.underlineLinks);
            setPref("sendOnEnter", DEFAULT_PREFS.sendOnEnter);
            setPref("iHateAdas", DEFAULT_PREFS.iHateAdas);
          }}
        >
          {t("accessibilityTab.resetToDefaults")}
        </Button>
      </div>
    </div>
  );
}
