import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot as BotIcon, Check, Copy, KeyRound, Menu, Plus, Trash2 } from "lucide-react";
import { Button } from "../../components/ui/Button";
import { ImageField } from "../../components/ImageField";
import { formatFullTime } from "../../lib/time";
import { panelActions } from "../../stores/panels";
import { useServers } from "../servers/queries";
import {
  addBotToServer,
  createBot,
  deleteBot,
  listBotTokens,
  listBots,
  mintBotToken,
  revokeBotToken,
  updateBot,
  type Bot,
  type MintedToken,
} from "./api";
import { t, tNodes } from "../../lib/i18n";

const botKeys = {
  all: ["bots"] as const,
  tokens: (id: string) => ["bots", id, "tokens"] as const,
};


function TokenReveal({ token, onDone }: { token: MintedToken; onDone: () => void }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(token.token);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="rounded-lg border border-primary/40 bg-primary/5 p-3">
      <p className="text-sm font-medium">{t("developersPage.copyThisTokenNow")}</p>
      <p className="mt-1 text-xs text-ink-secondary">
        {t("developersPage.itIsStoredHashedAndCannot")}
      </p>
      <div className="mt-2 flex items-center gap-2">
        <code className="min-w-0 flex-1 overflow-x-auto rounded bg-surface-1 px-2 py-1.5 font-mono text-xs">
          {token.token}
        </code>
        <Button size="sm" variant="secondary" onClick={copy}>
          {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
          {copied ? "Copied" : "Copy"}
        </Button>
      </div>
      <Button size="sm" variant="ghost" className="mt-2" onClick={onDone}>
        {t("developersPage.iveSavedIt")}
      </Button>
    </div>
  );
}

function InviteToServer({ bot }: { bot: Bot }) {
  const { data: servers } = useServers();
  const [serverId, setServerId] = useState("");
  const [done, setDone] = useState(false);

  const invite = useMutation({
    mutationFn: () => addBotToServer(serverId, bot.id, "0"),
    onSuccess: () => {
      setDone(true);
      setTimeout(() => setDone(false), 3000);
    },
  });

  return (
    <div className="mt-3 flex flex-wrap items-center gap-2">
      <select
        value={serverId}
        onChange={(e) => setServerId(e.target.value)}
        className="h-8 rounded-lg border border-border-strong bg-surface-3 px-2 text-sm"
      >
        <option value="">{t("developersPage.addToServer")}</option>
        {(servers ?? []).map((s) => (
          <option key={s.id} value={s.id}>
            {s.name}
          </option>
        ))}
      </select>
      <Button
        size="sm"
        variant="secondary"
        disabled={!serverId || invite.isPending}
        loading={invite.isPending}
        onClick={() => invite.mutate()}
      >
        {done ? "Added" : "Add"}
      </Button>
      {invite.error ? (
        <span className="text-xs text-danger">{(invite.error as Error).message}</span>
      ) : null}
    </div>
  );
}

function TokenList({ bot }: { bot: Bot }) {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: botKeys.tokens(bot.id),
    queryFn: () => listBotTokens(bot.id),
  });
  const [minted, setMinted] = useState<MintedToken | null>(null);

  const mint = useMutation({
    mutationFn: () => mintBotToken(bot.id),
    onSuccess: (token) => {
      setMinted(token);
      void qc.invalidateQueries({ queryKey: botKeys.tokens(bot.id) });
    },
  });

  const revoke = useMutation({
    mutationFn: (tokenId: string) => revokeBotToken(bot.id, tokenId),
    onSuccess: () => qc.invalidateQueries({ queryKey: botKeys.tokens(bot.id) }),
  });

  return (
    <div className="mt-3">
      <div className="flex items-center justify-between">
        <p className="text-xs font-medium text-ink-secondary">{t("developersPage.tokens")}</p>
        <Button size="sm" variant="ghost" loading={mint.isPending} onClick={() => mint.mutate()}>
          <KeyRound className="size-4" />
          {t("developersPage.newToken")}
        </Button>
      </div>

      {minted ? (
        <div className="mt-2">
          <TokenReveal token={minted} onDone={() => setMinted(null)} />
        </div>
      ) : null}

      <ul className="mt-2 space-y-1">
        {(data?.tokens ?? []).map((token) => (
          <li
            key={token.id}
            className="flex items-center gap-2 rounded-lg border border-border px-2.5 py-1.5"
          >
            <code className="font-mono text-xs text-ink-secondary">…{token.hint}</code>
            <span className="min-w-0 flex-1 truncate text-xs text-ink-secondary">
              {token.lastUsedAt
                ? `last used ${formatFullTime(token.lastUsedAt)}`
                : `created ${formatFullTime(token.createdAt)}, never used`}
            </span>
            <Button
              size="sm"
              variant="ghost"
              aria-label={t("developersPage.revokeToken")}
              onClick={() => revoke.mutate(token.id)}
            >
              <Trash2 className="size-4" />
            </Button>
          </li>
        ))}
        {data && data.tokens.length === 0 ? (
          <li className="px-2.5 py-1.5 text-xs text-ink-secondary">
            {t("developersPage.noTokensThisBotCannotSign")}
          </li>
        ) : null}
      </ul>
    </div>
  );
}

function BotCard({ bot }: { bot: Bot }) {
  const qc = useQueryClient();
  const [displayName, setDisplayName] = useState(bot.displayName);
  const [avatar, setAvatar] = useState({ url: bot.avatarUrl ?? "", preview: "" });
  const [confirmDelete, setConfirmDelete] = useState(false);

  const save = useMutation({
    mutationFn: () => updateBot(bot.id, { displayName, avatarUrl: avatar.url || null }),
    onSuccess: () => qc.invalidateQueries({ queryKey: botKeys.all }),
  });

  const remove = useMutation({
    mutationFn: () => deleteBot(bot.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: botKeys.all }),
  });

  const dirty = displayName !== bot.displayName || avatar.url !== (bot.avatarUrl ?? "");

  return (
    <li className="rounded-lg border border-border p-3">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate text-sm font-medium">{bot.displayName}</p>
            <span className="rounded bg-primary/15 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-primary">
              {t("developersPage.bot")}
            </span>
          </div>
          <p className="text-xs text-ink-secondary">@{bot.username}</p>
        </div>
        <Button
          size="sm"
          variant={confirmDelete ? "danger" : "ghost"}
          loading={remove.isPending}
          onClick={() => (confirmDelete ? remove.mutate() : setConfirmDelete(true))}
          onBlur={() => setConfirmDelete(false)}
        >
          <Trash2 className="size-4" />
          {confirmDelete ? "Delete for good" : null}
        </Button>
      </div>

      <div className="mt-3 space-y-2">
        <label className="block text-xs font-medium text-ink-secondary">
          {t("developersPage.displayName")}
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            maxLength={32}
            className="mt-1 h-9 w-full rounded-lg border border-border-strong bg-surface-1 px-2.5 text-sm text-ink"
          />
        </label>
        <ImageField
          label={t("developersPage.avatar")}
          kind="avatar"
          value={avatar.url}
          preview={avatar.preview}
          onChange={(url, preview) => setAvatar({ url, preview })}
        />
        {dirty ? (
          <Button size="sm" loading={save.isPending} onClick={() => save.mutate()}>
            {t("developersPage.saveChanges")}
          </Button>
        ) : null}
      </div>

      <TokenList bot={bot} />
      <InviteToServer bot={bot} />
    </li>
  );
}

function CreateBot() {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [minted, setMinted] = useState<MintedToken | null>(null);

  const create = useMutation({
    mutationFn: () => createBot(username.trim().toLowerCase(), displayName.trim() || username),
    onSuccess: (res) => {
      setMinted(res.token);
      setUsername("");
      setDisplayName("");
      setOpen(false);
      void qc.invalidateQueries({ queryKey: botKeys.all });
    },
  });

  if (minted) {
    return (
      <div className="mb-3">
        <TokenReveal token={minted} onDone={() => setMinted(null)} />
      </div>
    );
  }

  if (!open) {
    return (
      <Button size="sm" variant="secondary" className="mb-3" onClick={() => setOpen(true)}>
        <Plus className="size-4" />
        {t("developersPage.newBot")}
      </Button>
    );
  }

  return (
    <div className="mb-3 rounded-lg border border-border p-3">
      <div className="space-y-2">
        <label className="block text-xs font-medium text-ink-secondary">
          {t("developersPage.username")}
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="helper_bot"
            maxLength={32}
            className="mt-1 h-9 w-full rounded-lg border border-border-strong bg-surface-1 px-2.5 text-sm text-ink"
          />
        </label>
        <label className="block text-xs font-medium text-ink-secondary">
          {t("developersPage.displayName")}
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder={t("developersPage.helper")}
            maxLength={32}
            className="mt-1 h-9 w-full rounded-lg border border-border-strong bg-surface-1 px-2.5 text-sm text-ink"
          />
        </label>
      </div>
      {create.error ? (
        <p className="mt-2 text-xs text-danger">{(create.error as Error).message}</p>
      ) : null}
      <div className="mt-3 flex gap-2">
        <Button
          size="sm"
          disabled={username.trim().length < 2}
          loading={create.isPending}
          onClick={() => create.mutate()}
        >
          {t("common.create")}
        </Button>
        <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
          {t("common.cancel")}
        </Button>
      </div>
    </div>
  );
}

export function DevelopersPage() {
  const { data, isLoading } = useQuery({ queryKey: botKeys.all, queryFn: listBots });

  return (
    <div className="flex flex-1 flex-col bg-surface-2">
      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-border px-4">
        <button
          type="button"
          onClick={panelActions.openLeft}
          aria-label={t("developersPage.openNavigation")}
          className="rounded-lg p-1.5 text-ink-secondary transition-colors hover:bg-surface-3 hover:text-ink md:hidden"
        >
          <Menu aria-hidden className="size-5" />
        </button>
        <span className="font-semibold">{t("developersPage.developers")}</span>
      </header>

      <div className="mx-auto w-full max-w-2xl flex-1 overflow-y-auto p-4">
      <h2 className="mb-1 text-base font-semibold">{t("developersPage.bots")}</h2>
      <p className="mb-3 text-sm text-ink-secondary">
        {tNodes("developersPage.botsIntro", {
          js: (
            <code className="rounded bg-surface-3 px-1 py-0.5 font-mono text-xs">
              @orangchat/bot
            </code>
          ),
          py: (
            <code className="rounded bg-surface-3 px-1 py-0.5 font-mono text-xs">orangchat</code>
          ),
        })}
      </p>

      <CreateBot />

      {isLoading ? (
        <p className="text-sm text-ink-secondary">{t("common.loading")}</p>
      ) : data && data.bots.length > 0 ? (
        <ul className="space-y-3">
          {data.bots.map((bot) => (
            <BotCard key={bot.id} bot={bot} />
          ))}
        </ul>
      ) : (
        <div className="rounded-lg border border-dashed border-border px-4 py-8 text-center">
          <BotIcon aria-hidden className="mx-auto size-6 text-ink-secondary" />
          <p className="mt-2 text-sm text-ink-secondary">{t("developersPage.youHaventMadeAnyBotsYet")}</p>
        </div>
      )}
      </div>
    </div>
  );
}
