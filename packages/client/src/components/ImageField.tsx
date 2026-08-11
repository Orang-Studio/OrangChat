import { useRef, useState } from "react";
import { Upload } from "lucide-react";
import { Button } from "./ui/Button";
import { cn } from "../lib/cn";
import { uploadImage, type UploadKind } from "../features/uploads/api";
import { t } from "../lib/i18n";


function ImageUploadButton({
  kind,
  label,
  disabled,
  onUploaded,
}: {
  kind: UploadKind;
  label: string;
  disabled?: boolean;
  onUploaded: (url: string, preview: string) => void;
}) {
  const ref = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onFile = async (file: File | undefined) => {
    setError(null);
    if (!file) return;
    setBusy(true);
    try {
      const url = await uploadImage(file, kind);
      onUploaded(url, URL.createObjectURL(file));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Upload failed");
    } finally {
      setBusy(false);
      if (ref.current) ref.current.value = "";
    }
  };

  return (
    <div className="mt-1.5">
      <input
        ref={ref}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp"
        className="hidden"
        onChange={(e) => void onFile(e.target.files?.[0] ?? undefined)}
      />
      <Button
        type="button"
        variant="secondary"
        size="sm"
        loading={busy}
        disabled={disabled}
        onClick={() => ref.current?.click()}
      >
        <Upload aria-hidden className="size-4" />
        {label}
      </Button>
      {error && <p className="mt-1 text-xs text-danger">{error}</p>}
    </div>
  );
}


export function ImageField({
  label,
  kind,
  value,
  preview,
  onChange,
  hint,
  disabled,
  rounded = "full",
}: {
  label: string;
  kind: UploadKind;
  value: string;

  preview?: string;
  onChange: (url: string, preview: string) => void;
  hint?: string;
  disabled?: boolean;
  rounded?: "full" | "md";
}) {
  const shown = preview || value;
  return (
    <div>
      <label className="mb-1.5 block text-sm font-medium text-ink-secondary">{label}</label>
      <div className="flex items-center gap-3">
        {shown ? (
          <img
            src={shown}
            alt=""
            className={cn(
              "size-14 shrink-0 border border-border object-cover",
              rounded === "full" ? "rounded-full" : "rounded-lg",
            )}
          />
        ) : (
          <span
            aria-hidden
            className={cn(
              "flex size-14 shrink-0 items-center justify-center border border-dashed border-border bg-surface-1 text-ink-muted",
              rounded === "full" ? "rounded-full" : "rounded-lg",
            )}
          >
            <Upload className="size-5" />
          </span>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <ImageUploadButton
            kind={kind}
            label={t("common.upload")}
            disabled={disabled}
            onUploaded={onChange}
          />
          {value && !disabled && (
            <Button type="button" variant="ghost" size="sm" onClick={() => onChange("", "")}>
              {t("common.remove")}
            </Button>
          )}
        </div>
      </div>
      {hint && <p className="mt-1.5 text-xs text-ink-muted">{hint}</p>}
    </div>
  );
}
