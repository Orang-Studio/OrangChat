import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BadgeCheck, Eye, EyeOff, Link2, Plus, Trash2 } from "lucide-react";
import type { Connection, ConnectionProvider } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { TextField } from "../../components/ui/TextField";
import {
  addCustomConnection,
  getConnectionProviders,
  getMyConnections,
  removeConnection,
  setConnectionVisible,
  startConnectionLink,
} from "../connections/api";
import { providerLabel, ProviderIcon } from "../connections/icons";
import { SectionTitle } from "./controls";
import { t } from "../../lib/i18n";


const RESULT_MESSAGE: Record<string, string> = {
  linked: "Account linked.",
  cancelled: "Linking was cancelled.",
  failed: "That account couldn't be linked. Please try again.",
};


function useLinkResult() {
  const [result, setResult] = useState<string | null>(null);
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const value = params.get("connection");
    if (!value) return;
    setResult(value);
    params.delete("connection");
    params.delete("provider");
    const query = params.toString();
    window.history.replaceState({}, "", window.location.pathname + (query ? `?${query}` : ""));
  }, []);
  return result;
}

function ConnectionRow({ connection }: { connection: Connection }) {
  const client = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const invalidate = () => client.invalidateQueries({ queryKey: ["connections", "mine"] });

  const visibility = useMutation({
    mutationFn: () => setConnectionVisible(connection.id, !connection.visible),
    onSuccess: invalidate,
  });
  const disconnect = useMutation({
    mutationFn: () => removeConnection(connection.id),
    onSuccess: () => {
      setConfirming(false);
      void invalidate();
    },
  });

  return (
    <li className="flex items-center gap-3 rounded-lg border border-border px-3 py-2.5">
      <ProviderIcon provider={connection.provider} className="size-5 shrink-0 text-ink-secondary" />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1">
          <span className="truncate text-sm font-medium">{connection.name}</span>
          {connection.verified && (
            <BadgeCheck aria-label={t("connectionsTab.verified")} className="size-3.5 shrink-0 text-primary" />
          )}
        </div>
        <p className="truncate text-xs text-ink-muted">
          {connection.provider === "custom"
            ? (connection.profileUrl ?? "Custom link")
            : providerLabel(connection.provider)}
          {!connection.visible && " · hidden from your profile"}
        </p>
      </div>
      <Button
        variant="ghost"
        size="sm"
        loading={visibility.isPending}
        onClick={() => visibility.mutate()}
        aria-label={connection.visible ? "Hide from profile" : "Show on profile"}
        title={connection.visible ? "Hide from profile" : "Show on profile"}
      >
        {connection.visible ? (
          <Eye aria-hidden className="size-4" />
        ) : (
          <EyeOff aria-hidden className="size-4" />
        )}
      </Button>
      <Button
        variant="ghost"
        size="sm"
        className="text-danger hover:text-danger"
        onClick={() => setConfirming(true)}
        aria-label={t("connectionsTab.disconnect")}
        title={t("connectionsTab.disconnect")}
      >
        <Trash2 aria-hidden className="size-4" />
      </Button>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={`Disconnect ${connection.name}?`}
        description={t("connectionsTab.itWillBeRemovedFromYour")}
        confirmLabel={t("connectionsTab.disconnect")}
        danger
        loading={disconnect.isPending}
        error={disconnect.error instanceof Error ? disconnect.error.message : null}
        onConfirm={() => disconnect.mutate()}
      />
    </li>
  );
}

function AddCustomLink() {
  const client = useQueryClient();
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");

  const add = useMutation({
    mutationFn: () => addCustomConnection(name.trim(), url.trim()),
    onSuccess: () => {
      setName("");
      setUrl("");
      void client.invalidateQueries({ queryKey: ["connections", "mine"] });
    },
  });

  const ready = name.trim().length > 0 && /^https?:\/\/.+/.test(url.trim());

  return (
    <form
      className="mt-2 flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (ready) add.mutate();
      }}
    >
      {/* TextField spreads className onto the input, so the flex sizing has to
          live on a wrapper or the fields won't share the row. */}
      <div className="min-w-32 flex-1">
        <TextField
          label={t("connectionsTab.label")}
          value={name}
          maxLength={40}
          placeholder={t("connectionsTab.myWebsite")}
          onChange={(e) => setName(e.target.value)}
        />
      </div>
      <div className="min-w-48 flex-[2]">
        <TextField
          label={t("connectionsTab.link")}
          type="url"
          value={url}
          maxLength={500}
          placeholder="https://example.com"
          onChange={(e) => setUrl(e.target.value)}
        />
      </div>
      <Button type="submit" variant="secondary" disabled={!ready} loading={add.isPending}>
        <Plus aria-hidden className="size-4" />
        {t("common.add")}
      </Button>
      {add.error && (
        <p className="w-full text-xs text-danger">
          {add.error instanceof Error ? add.error.message : "Couldn't add that link"}
        </p>
      )}
    </form>
  );
}

export function ConnectionsTab() {
  const result = useLinkResult();
  const { data: connections, isLoading } = useQuery({
    queryKey: ["connections", "mine"],
    queryFn: getMyConnections,
  });
  const { data: providers } = useQuery({
    queryKey: ["connections", "providers"],
    queryFn: getConnectionProviders,
  });

  const [linking, setLinking] = useState<ConnectionProvider | null>(null);
  const [linkError, setLinkError] = useState<string | null>(null);

  const connect = async (provider: ConnectionProvider) => {
    setLinking(provider);
    setLinkError(null);
    try {
      // On success this navigates away, so there's no state to reset.
      await startConnectionLink(provider);
    } catch (e) {
      setLinkError(e instanceof Error ? e.message : "Couldn't start linking");
      setLinking(null);
    }
  };

  return (
    <div className="space-y-6">
      {result && RESULT_MESSAGE[result] && (
        <p
          role="status"
          className={
            result === "linked"
              ? "rounded-md border border-primary bg-primary-soft px-3 py-2 text-sm"
              : "rounded-md border border-border px-3 py-2 text-sm text-ink-secondary"
          }
        >
          {RESULT_MESSAGE[result]}
        </p>
      )}

      <section>
        <SectionTitle>{t("connectionsTab.connectAnAccount")}</SectionTitle>
        <p className="mb-2 text-xs text-ink-muted">
          {t("connectionsTab.linkedAccountsAppearOnYourProfile")}
        </p>
        <div className="flex flex-wrap gap-2">
          {providers?.map((p) => (
            <Button
              key={p.key}
              variant="secondary"
              size="sm"
              loading={linking === p.key}
              onClick={() => void connect(p.key)}
            >
              <ProviderIcon provider={p.key} className="size-4" />
              {p.label}
            </Button>
          ))}
        </div>
        {providers?.length === 0 && (
          <p className="text-sm text-ink-muted">
            {t("connectionsTab.noProvidersAreConfiguredOnThis")}
          </p>
        )}
        {linkError && <p className="mt-2 text-xs text-danger">{linkError}</p>}
      </section>

      <section>
        <SectionTitle>{t("connectionsTab.addAnyWebsite")}</SectionTitle>
        <p className="text-xs text-ink-muted">
          {t("connectionsTab.linksYouAddByHandShow")}
        </p>
        <AddCustomLink />
      </section>

      <section>
        <SectionTitle>{t("connectionsTab.yourConnections")}</SectionTitle>
        {isLoading ? (
          <p className="text-sm text-ink-muted">{t("common.loading")}</p>
        ) : connections && connections.length > 0 ? (
          <ul className="space-y-1.5">
            {connections.map((c) => (
              <ConnectionRow key={c.id} connection={c} />
            ))}
          </ul>
        ) : (
          <p className="flex items-center gap-2 text-sm text-ink-muted">
            <Link2 aria-hidden className="size-4" />
            {t("connectionsTab.nothingLinkedYet")}
          </p>
        )}
      </section>
    </div>
  );
}
