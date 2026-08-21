import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ProfileFieldTokenInfo } from "@orangchat/shared";
import { Copy, KeyRound, Plus, Trash2 } from "lucide-react";
import { Button } from "../../components/ui/Button";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { cn } from "../../lib/cn";
import { t } from "../../lib/i18n";
import { formatFullTime } from "../../lib/time";
import { toast } from "../../stores/toasts";
import { listFieldTokens, mintFieldToken, revokeFieldToken } from "./fieldTokens";

const TOKENS_KEY = ["profile", "field-tokens"] as const;

function CopyRow({ value }: { value: string }) {
  return (
    <div className="flex items-stretch gap-2">
      <code className="min-w-0 flex-1 break-all rounded-lg border border-border bg-surface-2 px-2.5 py-2 font-mono text-xs">
        {value}
      </code>
      <Button
        type="button"
        variant="secondary"
        size="sm"
        onClick={() => {
          void navigator.clipboard
            .writeText(value)
            .then(() => toast.success(t("fieldTokens.copied")))
            .catch(() => toast.error(t("fieldTokens.copyFailed")));
        }}
      >
        <Copy aria-hidden className="size-4" />
        {t("common.copy")}
      </Button>
    </div>
  );
}


export function FieldTokensSection({ fields }: { fields: Record<string, string> }) {
  const queryClient = useQueryClient();
  const [label, setLabel] = useState("");
  const [minted, setMinted] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<ProfileFieldTokenInfo | null>(null);

  const tokens = useQuery({ queryKey: TOKENS_KEY, queryFn: listFieldTokens });

  const mint = useMutation({
    mutationFn: (name: string) => mintFieldToken(name),
    onSuccess: (result) => {
      setMinted(result.token);
      setLabel("");
      void queryClient.invalidateQueries({ queryKey: TOKENS_KEY });
    },
  });

  const revoke = useMutation({
    mutationFn: (id: string) => revokeFieldToken(id),
    onSuccess: () => {
      setRevoking(null);
      void queryClient.invalidateQueries({ queryKey: TOKENS_KEY });
    },
  });

  const error = mint.error ?? revoke.error ?? tokens.error;

  return (
    <div className="space-y-3">
      <p className="text-xs text-ink-muted">
        {t("fieldTokens.intro", { field: { name: "{field.name}" } })}
      </p>

      <div className="space-y-1.5">
        <p className="text-xs font-medium text-ink-secondary">{t("fieldTokens.howToPush")}</p>
        <pre className="overflow-x-auto rounded-lg border border-border bg-surface-2 p-2.5 font-mono text-[11px] leading-relaxed text-ink-secondary">
          {`POST ${window.location.origin}/api/profile/fields
Authorization: Widget <token>
Content-Type: application/json

{"field": "status", "value": "shipping things"}`}
        </pre>
        <p className="text-xs text-ink-muted">
          {t("fieldTokens.thenUse", { field: { status: "{field.status}" } })}
        </p>
      </div>

      {Object.keys(fields).length > 0 && (
        <div className="space-y-1.5">
          <p className="text-xs font-medium text-ink-secondary">{t("fieldTokens.currentValues")}</p>
          <div className="space-y-1 rounded-lg border border-border bg-surface-2 p-2.5">
            {Object.entries(fields).map(([key, value]) => (
              <div key={key} className="flex gap-2.5 text-xs">
                <code className="shrink-0 font-mono text-ink-muted">{`{field.${key}}`}</code>
                <span className="min-w-0 truncate text-ink">{value}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {minted && (
        <div className="space-y-2 rounded-lg border border-primary bg-primary/[0.06] p-2.5">
          <p className="text-xs font-medium text-ink">{t("fieldTokens.copyItNow")}</p>
          <CopyRow value={minted} />
          <Button type="button" variant="ghost" size="sm" onClick={() => setMinted(null)}>
            {t("fieldTokens.dismiss")}
          </Button>
        </div>
      )}

      {(tokens.data ?? []).map((token) => (
        <div
          key={token.id}
          className="flex items-center gap-2.5 rounded-lg border border-border bg-surface-1 px-2.5 py-2"
        >
          <KeyRound aria-hidden className="size-4 shrink-0 text-ink-muted" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-ink">{token.label}</p>
            <p className="truncate text-xs text-ink-muted">
              {`••••${token.hint}`} ·{" "}
              {token.lastUsedAt
                ? t("fieldTokens.lastUsed", { when: formatFullTime(token.lastUsedAt) })
                : t("fieldTokens.neverUsed")}
            </p>
          </div>
          <button
            type="button"
            aria-label={t("fieldTokens.revoke")}
            onClick={() => setRevoking(token)}
            className="rounded p-1 text-ink-muted transition-colors hover:text-danger"
          >
            <Trash2 aria-hidden className="size-4" />
          </button>
        </div>
      ))}

      <div className="flex items-end gap-2">
        <label className="min-w-0 flex-1 space-y-1">
          <span className="sr-only">{t("fieldTokens.tokenName")}</span>
          <input
            value={label}
            maxLength={60}
            placeholder={t("fieldTokens.tokenNamePlaceholder")}
            onChange={(e) => setLabel(e.target.value)}
            className="w-full rounded-lg border border-border bg-surface-1 px-2.5 py-1.5 text-sm text-ink placeholder:text-ink-muted hover:border-border-strong"
          />
        </label>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={mint.isPending}
          onClick={() =>
            mint.mutate(label.trim() || t("fieldTokens.untitled"))
          }
        >
          <Plus aria-hidden className="size-4" />
          {t("fieldTokens.create")}
        </Button>
      </div>

      {error instanceof Error && <p className="text-xs text-danger">{error.message}</p>}

      <ConfirmDialog
        open={revoking != null}
        onOpenChange={(open) => {
          if (!open) setRevoking(null);
        }}
        title={t("fieldTokens.revoke")}
        description={
          revoking
            ? t("fieldTokens.revokeDescription", { name: revoking.label })
            : undefined
        }
        confirmLabel={t("fieldTokens.revoke")}
        danger
        loading={revoke.isPending}
        onConfirm={() => revoking && revoke.mutate(revoking.id)}
      />
    </div>
  );
}
