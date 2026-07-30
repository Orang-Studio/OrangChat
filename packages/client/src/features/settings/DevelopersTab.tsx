import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot as BotIcon, Check, Copy, KeyRound, Plus, Trash2 } from "lucide-react";
import { Button } from "../../components/ui/Button";
import { ImageField } from "../../components/ImageField";
import { formatFullTime } from "../../lib/time";
import { useServers } from "../servers/queries";
import { SectionTitle } from "./controls";
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
} from "./botsApi";

const botKeys = {
  all: ["bots"] as const,
  tokens: (id: string) => ["bots", id, "tokens"] as const,
};

/**
 * The one moment a token is visible. The server stores only a digest, so if this
 * is dismissed without copying, the token is gone and a new one must be minted -
 * the copy says so plainly rather than letting someone find out later.
 */
function TokenReveal({ token, onDone }: { token: MintedToken; onDone: () => void }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(token.token);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="rounded-lg border border-primary/40 bg-primary/5 p-3">
      <p className="text-sm font-medium">Copy this token now</p>
      <p className="mt-1 text-xs text-ink-secondary">
        It is stored hashed and cannot be shown again. If you lose it, mint a new one.
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
        I've saved it
      </Button>
    </div>
  );
}

function InviteToServer({ bot }: { bot: Bot }) {
  const { data: servers } = useServers();
  const [serverId, setServerId] = useState("");
  const [done, setDone] = useState(false);

  const invite = useMutation({
    // No permission bits: the bot joins with the server's @everyone defaults and
    // is given more from the roles screen, the same as any other member. The
    // server refuses anything above the inviter's own permissions regardless.
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
        <option value="">Add to server…</option>
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
        <p className="text-xs font-medium text-ink-secondary">Tokens</p>
        <Button size="sm" variant="ghost" loading={mint.isPending} onClick={() => mint.mutate()}>
          <KeyRound className="size-4" />
          New token
        </Button>
      </div>

      {minted ? (
        <div className="mt-2">
          <TokenReveal token={minted} onDone={() => setMinted(null)} />
        </div>
      ) : null}

      <ul className="mt-2 space-y-1">
        {(data?.tokens ?? []).map((t) => (
          <li
            key={t.id}
            className="flex items-center gap-2 rounded-lg border border-border px-2.5 py-1.5"
          >
            <code className="font-mono text-xs text-ink-secondary">…{t.hint}</code>
            <span className="min-w-0 flex-1 truncate text-xs text-ink-secondary">
              {t.lastUsedAt
                ? `last used ${formatFullTime(t.lastUsedAt)}`
                : `created ${formatFullTime(t.createdAt)}, never used`}
            </span>
            <Button
              size="sm"
              variant="ghost"
              aria-label="Revoke token"
              onClick={() => revoke.mutate(t.id)}
            >
              <Trash2 className="size-4" />
            </Button>
          </li>
        ))}
        {data && data.tokens.length === 0 ? (
          <li className="px-2.5 py-1.5 text-xs text-ink-secondary">
            No tokens. This bot cannot sign in until you mint one.
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
              Bot
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
          Display name
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            maxLength={32}
            className="mt-1 h-9 w-full rounded-lg border border-border-strong bg-surface-1 px-2.5 text-sm text-ink"
          />
        </label>
        <ImageField
          label="Avatar"
          kind="avatar"
          value={avatar.url}
          preview={avatar.preview}
          onChange={(url, preview) => setAvatar({ url, preview })}
        />
        {dirty ? (
          <Button size="sm" loading={save.isPending} onClick={() => save.mutate()}>
            Save changes
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
        New bot
      </Button>
    );
  }

  return (
    <div className="mb-3 rounded-lg border border-border p-3">
      <div className="space-y-2">
        <label className="block text-xs font-medium text-ink-secondary">
          Username
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="helper_bot"
            maxLength={32}
            className="mt-1 h-9 w-full rounded-lg border border-border-strong bg-surface-1 px-2.5 text-sm text-ink"
          />
        </label>
        <label className="block text-xs font-medium text-ink-secondary">
          Display name
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Helper"
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
          Create
        </Button>
        <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
          Cancel
        </Button>
      </div>
    </div>
  );
}

export function DevelopersTab() {
  const { data, isLoading } = useQuery({ queryKey: botKeys.all, queryFn: listBots });

  return (
    <div>
      <SectionTitle>Bots</SectionTitle>
      <p className="mb-3 text-sm text-ink-secondary">
        Build on OrangChat with the{" "}
        <code className="rounded bg-surface-3 px-1 py-0.5 font-mono text-xs">@orangchat/bot</code>{" "}
        package for JavaScript or{" "}
        <code className="rounded bg-surface-3 px-1 py-0.5 font-mono text-xs">orangchat</code> for
        Python. A bot is a real account: invite it to a server and give it roles like anyone else.
        Bots cannot read DMs — those are end-to-end encrypted.
      </p>

      <CreateBot />

      {isLoading ? (
        <p className="text-sm text-ink-secondary">Loading…</p>
      ) : data && data.bots.length > 0 ? (
        <ul className="space-y-3">
          {data.bots.map((bot) => (
            <BotCard key={bot.id} bot={bot} />
          ))}
        </ul>
      ) : (
        <div className="rounded-lg border border-dashed border-border px-4 py-8 text-center">
          <BotIcon aria-hidden className="mx-auto size-6 text-ink-secondary" />
          <p className="mt-2 text-sm text-ink-secondary">You haven't made any bots yet.</p>
        </div>
      )}
    </div>
  );
}
