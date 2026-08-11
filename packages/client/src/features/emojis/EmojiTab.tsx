import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Trash2, Upload } from "lucide-react";
import type { Emoji, Server } from "@orangchat/shared";
import { MAX_EMOJI_BYTES, deleteEmoji, renameEmoji, uploadEmoji } from "./api";
import { emojiKeys, useServerEmojis } from "./queries";
import { t } from "../../lib/i18n";

/**
 * A filename is the best guess at a name, and usually a good one: `blob_wave.png`
 * wants to be `blob_wave`. Strip the extension and anything the server would
 * reject rather than making the user retype it.
 */
function nameFromFile(file: File): string {
  const base = file.name.replace(/\.[^.]+$/, "");
  const cleaned = base.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 32);
  return cleaned.length >= 2 ? cleaned : "emoji";
}

function EmojiRow({ server, emoji }: { server: Server; emoji: Emoji }) {
  const client = useQueryClient();
  const [name, setName] = useState(emoji.name);
  const [error, setError] = useState<string | null>(null);

  const invalidate = () => {
    void client.invalidateQueries({ queryKey: emojiKeys.server(server.id) });
    void client.invalidateQueries({ queryKey: emojiKeys.usable });
  };

  const rename = useMutation({
    mutationFn: () => renameEmoji(server.id, emoji.id, name),
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: (err: Error) => {
      // Put the rejected name back so the row keeps matching the server.
      setName(emoji.name);
      setError(err.message);
    },
  });

  const remove = useMutation({
    mutationFn: () => deleteEmoji(server.id, emoji.id),
    onSuccess: invalidate,
    onError: (err: Error) => setError(err.message),
  });

  return (
    <li className="flex items-center gap-3 rounded-lg border border-border bg-surface-2 p-2">
      <img
        src={emoji.url}
        alt={`:${emoji.name}:`}
        loading="lazy"
        className="size-8 shrink-0 object-contain"
      />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1 text-sm">
          <span className="text-ink-muted">:</span>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            // Commit on blur/Enter rather than per-keystroke: every character
            // would otherwise be a PATCH, and each one a chance to 409.
            onBlur={() => name !== emoji.name && rename.mutate()}
            onKeyDown={(e) => e.key === "Enter" && e.currentTarget.blur()}
            aria-label={`Rename :${emoji.name}:`}
            className="min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 py-0.5 outline-none hover:border-border focus:border-primary"
          />
          <span className="text-ink-muted">:</span>
        </div>
        {error && (
          <p role="alert" className="px-1 text-xs text-danger">
            {error}
          </p>
        )}
      </div>
      {emoji.animated && (
        <span className="shrink-0 rounded border border-border px-1 text-[10px] font-semibold uppercase text-ink-muted">
          {t("emojiTab.gif")}
        </span>
      )}
      <button
        type="button"
        onClick={() => remove.mutate()}
        disabled={remove.isPending}
        aria-label={`Delete :${emoji.name}:`}
        title={`Delete :${emoji.name}:`}
        className="shrink-0 rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-3 hover:text-danger disabled:opacity-50"
      >
        <Trash2 aria-hidden className="size-4" />
      </button>
    </li>
  );
}

export function EmojiTab({ server }: { server: Server }) {
  const client = useQueryClient();
  const { data: emojis, isLoading } = useServerEmojis(server.id);
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);

  const upload = useMutation({
    mutationFn: (file: File) => uploadEmoji(server.id, file, nameFromFile(file)),
    onSuccess: () => {
      setError(null);
      void client.invalidateQueries({ queryKey: emojiKeys.server(server.id) });
      void client.invalidateQueries({ queryKey: emojiKeys.usable });
    },
    onError: (err: Error) => setError(err.message),
  });

  const onFiles = (files: FileList | null) => {
    const file = files?.[0];
    if (!file) return;
    // Checked here as well as server-side so an oversized file fails instantly
    // instead of after uploading 256 kB+ of it.
    if (file.size > MAX_EMOJI_BYTES) {
      setError("Emoji must be 256 kB or smaller.");
      return;
    }
    upload.mutate(file);
  };

  return (
    <div className="space-y-4">
      <div>
        <div className="flex items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-semibold">{t("emojiTab.customEmoji")}</h3>
            <p className="text-xs text-ink-muted">
              {t("emojiTab.pngJpegOrGifUpTo")}
            </p>
          </div>
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            disabled={upload.isPending}
            className="flex shrink-0 items-center gap-2 rounded-lg bg-primary px-3 py-2 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            <Upload aria-hidden className="size-4" />
            {upload.isPending ? "Uploading…" : "Upload"}
          </button>
        </div>
        <input
          ref={inputRef}
          type="file"
          accept="image/png,image/jpeg,image/gif,image/webp"
          className="hidden"
          onChange={(e) => {
            onFiles(e.target.files);
            // Clear it, or picking the same file twice fires no change event.
            e.target.value = "";
          }}
        />
        {error && (
          <p role="alert" className="mt-2 text-xs text-danger">
            {error}
          </p>
        )}
      </div>

      {isLoading ? (
        <p className="text-sm text-ink-muted">{t("common.loading")}</p>
      ) : emojis && emojis.length > 0 ? (
        <ul className="max-h-80 space-y-2 overflow-y-auto">
          {emojis.map((emoji) => (
            <EmojiRow key={emoji.id} server={server} emoji={emoji} />
          ))}
        </ul>
      ) : (
        <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-ink-muted">
          {t("emojiTab.noCustomEmojiYetUploadOne")}
        </p>
      )}
    </div>
  );
}
