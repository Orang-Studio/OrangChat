import {
  DEFAULT_PREFS,
  FONT_SCALE_MAX,
  FONT_SCALE_MIN,
  setPref,
  usePrefs,
} from "../../lib/prefs";
import { Button } from "../../components/ui/Button";
import { SectionTitle, SegmentedControl, Toggle } from "./controls";

export function AccessibilityTab() {
  const prefs = usePrefs();

  return (
    <div className="space-y-6">
      <div>
        <SectionTitle>Text size</SectionTitle>
        <p className="mb-3 text-sm text-ink-secondary">
          Scales the entire interface. The sample below updates as you drag.
        </p>
        <input
          type="range"
          min={FONT_SCALE_MIN}
          max={FONT_SCALE_MAX}
          step={0.05}
          value={prefs.fontScale}
          onChange={(e) => setPref("fontScale", Number(e.target.value))}
          aria-label="Text size"
          className="w-full accent-[var(--oc-primary)]"
        />
        <div className="mt-2 flex items-center justify-between text-xs text-ink-muted">
          <span>Smaller</span>
          <span>{Math.round(prefs.fontScale * 100)}%</span>
          <span>Larger</span>
        </div>
        <p className="mt-3 rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm">
          The quick brown fox jumps over the lazy dog.
        </p>
      </div>

      <div className="border-t border-border pt-5">
        <SectionTitle>Message density</SectionTitle>
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
        <SectionTitle>Motion & contrast</SectionTitle>
        <Toggle
          checked={prefs.reducedMotion}
          onChange={(v) => setPref("reducedMotion", v)}
          label="Reduce motion"
          hint="Minimize animations and transitions across the app."
        />
        <Toggle
          checked={prefs.highContrast}
          onChange={(v) => setPref("highContrast", v)}
          label="Increase contrast"
          hint="Stronger borders and higher-contrast text."
        />
        <Toggle
          checked={prefs.underlineLinks}
          onChange={(v) => setPref("underlineLinks", v)}
          label="Underline links"
          hint="Always underline links, not just on hover."
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>Chat input</SectionTitle>
        <Toggle
          checked={prefs.sendOnEnter}
          onChange={(v) => setPref("sendOnEnter", v)}
          label="Send messages with Enter"
          hint={
            prefs.sendOnEnter
              ? "Enter sends, Shift+Enter adds a line break."
              : "Enter adds a line break, Ctrl/⌘+Enter sends."
          }
        />
      </div>

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
          }}
        >
          Reset to defaults
        </Button>
      </div>
    </div>
  );
}
