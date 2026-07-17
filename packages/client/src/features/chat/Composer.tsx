import { useEffect, useMemo, useRef, useState } from "react";
import { Paperclip, SendHorizontal, X } from "lucide-react";
import type { Message, ServerMember } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { usePrefs } from "../../lib/prefs";
import { Avatar } from "../../components/Avatar";
import { emitTyping, sendMessage } from "./socket-actions";
import { isEphemeral, MAX_PER_MESSAGE, uploadAttachment } from "./attachments";
import { ComposerAttachments, isSettled, type PendingUpload } from "./ComposerAttachments";
import { ExpressionPicker } from "./ExpressionPicker";

const TYPING_THROTTLE_MS = 2_500;
const MAX_LENGTH = 4_000;
const MENTION_LIMIT = 8;

interface ComposerProps {
  channelId: string;
  channelName: string;
  replyTo: Message | null;
  onClearReply: () => void;
  /** Members for @mention autocomplete (empty for DMs). */
  members?: ServerMember[];
}

const escapeRegex = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** Match a `@query` fragment ending at the caret (start-of-line or after space). */
function activeMention(value: string, caret: number): { start: number; query: string } | null {
  const before = value.slice(0, caret);
  const m = /(^|\s)@([^\s@]{0,32})$/.exec(before);
  if (!m) return null;
  const at = caret - m[2]!.length - 1;
  return { start: at, query: m[2]! };
}

export function Composer({
  channelId,
  channelName,
  replyTo,
  onClearReply,
  members = [],
}: ComposerProps) {
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [uploads, setUploads] = useState<PendingUpload[]>([]);
  const [dragging, setDragging] = useState(false);
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const lastTypingSent = useRef(0);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  // Display name (lowercased) → userId for mentions the user picked from the menu.
  const picked = useRef<Map<string, string>>(new Map());
  // Uploads outlive the render that started them; a ref keeps cleanup honest
  // even when the component unmounts mid-flight.
  const live = useRef<PendingUpload[]>([]);
  live.current = uploads;

  useEffect(() => {
    textarea.current?.focus();
  }, [replyTo, channelId]);

  // Reset per-draft mention bookkeeping when switching channels.
  useEffect(() => {
    picked.current.clear();
    setMention(null);
    setDraft("");
  }, [channelId]);

  // A draft belongs to its channel: switching away (or leaving) cancels uploads
  // in flight rather than silently attaching them to wherever you land next.
  useEffect(
    () => () => {
      for (const u of live.current) {
        u.abort();
        if (u.preview) URL.revokeObjectURL(u.preview);
      }
      setUploads([]);
    },
    [channelId],
  );

  const labelOf = (m: ServerMember) => m.nickname ?? m.user.displayName;

  const matches = useMemo(() => {
    if (!mention) return [];
    const q = mention.query.toLowerCase();
    return members
      .filter(
        (m) =>
          labelOf(m).toLowerCase().includes(q) ||
          m.user.username.toLowerCase().includes(q),
      )
      .slice(0, MENTION_LIMIT);
  }, [mention, members]);

  const syncMention = () => {
    const el = textarea.current;
    if (!el || members.length === 0) return setMention(null);
    const found = activeMention(el.value, el.selectionStart ?? el.value.length);
    setMention(found);
    setActiveIndex(0);
  };

  const onChange = (value: string) => {
    setDraft(value);
    setError(null);
    const now = Date.now();
    if (value && now - lastTypingSent.current > TYPING_THROTTLE_MS) {
      lastTypingSent.current = now;
      emitTyping(channelId);
    }
    // Defer so selectionStart reflects the new value.
    requestAnimationFrame(syncMention);
  };

  const pick = (m: ServerMember) => {
    if (!mention) return;
    const label = labelOf(m);
    const el = textarea.current;
    const caret = el?.selectionStart ?? draft.length;
    const next = `${draft.slice(0, mention.start)}@${label} ${draft.slice(caret)}`;
    picked.current.set(label.toLowerCase(), m.userId);
    setDraft(next);
    setMention(null);
    requestAnimationFrame(() => {
      const pos = mention.start + label.length + 2;
      el?.focus();
      el?.setSelectionRange(pos, pos);
    });
  };

  const patch = (key: string, next: Partial<PendingUpload>) =>
    setUploads((prev) => prev.map((u) => (u.key === key ? { ...u, ...next } : u)));

  const toggleSpoiler = (key: string) =>
    setUploads((prev) => prev.map((u) => (u.key === key ? { ...u, spoiler: !u.spoiler } : u)));

  const drop = (key: string) =>
    setUploads((prev) => {
      const found = prev.find((u) => u.key === key);
      found?.abort();
      if (found?.preview) URL.revokeObjectURL(found.preview);
      return prev.filter((u) => u.key !== key);
    });

  /**
   * Start uploading immediately on pick rather than at send: by the time the
   * user finishes typing, a large file is usually already up, and they can see
   * it failing instead of finding out after hitting send.
   */
  const addFiles = (files: File[]) => {
    if (files.length === 0) return;
    setError(null);

    const room = MAX_PER_MESSAGE - uploads.length;
    if (room <= 0) {
      setError(`A message can carry at most ${MAX_PER_MESSAGE} attachments`);
      return;
    }
    if (files.length > room) {
      setError(`Only the first ${room} of those fit on one message`);
      files = files.slice(0, room);
    }

    for (const file of files) {
      const key = crypto.randomUUID();
      const controller = new AbortController();
      const entry: PendingUpload = {
        key,
        name: file.name,
        size: file.size,
        ephemeral: isEphemeral(file),
        progress: 0,
        spoiler: false,
        preview: file.type.startsWith("image/") ? URL.createObjectURL(file) : undefined,
        abort: () => controller.abort(),
      };
      setUploads((prev) => [...prev, entry]);

      void uploadAttachment(file, {
        signal: controller.signal,
        onProgress: (fraction) => patch(key, { progress: fraction }),
      })
        .then((attachment) => patch(key, { attachment, progress: 1 }))
        .catch((err: unknown) => {
          // Cancelling is the user's own doing - drop(key) already removed the
          // entry, so there's nothing to report.
          if (err instanceof DOMException && err.name === "AbortError") return;
          patch(key, { error: err instanceof Error ? err.message : "Upload failed" });
        });
    }
  };

  /** Turn picked `@Display Name` tokens into `<@id>` wire mentions. */
  const encodeMentions = (text: string) => {
    let out = text;
    for (const [label, id] of picked.current) {
      out = out.replace(new RegExp(`@${escapeRegex(label)}(?=\\s|$)`, "gi"), `<@${id}>`);
    }
    return out;
  };

  const uploading = uploads.some((u) => !isSettled(u));
  const failed = uploads.filter((u) => u.error !== undefined);
  const ready = uploads.filter((u) => u.attachment !== undefined);
  // Attachments can carry a message on their own, so an empty draft is fine as
  // long as something is going with it.
  const canSend = (draft.trim().length > 0 || ready.length > 0) && !sending && !uploading;

  const send = async () => {
    if (!canSend) return;
    if (failed.length > 0) {
      setError("Remove the attachments that failed to upload first");
      return;
    }
    const content = encodeMentions(draft.trim());
    setSending(true);
    try {
      await sendMessage({
        channelId,
        content,
        replyToId: replyTo?.id,
        attachmentIds: ready.map((u) => u.attachment!.id),
        spoilerAttachmentIds: ready.filter((u) => u.spoiler).map((u) => u.attachment!.id),
      });
      setDraft("");
      picked.current.clear();
      for (const u of ready) if (u.preview) URL.revokeObjectURL(u.preview);
      setUploads([]);
      onClearReply();
    } catch (err) {
      // The uploads survive a failed send - they're still staged server-side, so
      // the same ids work on a retry.
      setError(err instanceof Error ? err.message : "Failed to send");
    } finally {
      setSending(false);
      textarea.current?.focus();
    }
  };

  const insertEmoji = (emoji: string) => {
    const el = textarea.current;
    const start = el?.selectionStart ?? draft.length;
    const end = el?.selectionEnd ?? start;
    const next = `${draft.slice(0, start)}${emoji}${draft.slice(end)}`.slice(0, MAX_LENGTH);
    onChange(next);
    requestAnimationFrame(() => {
      const caret = Math.min(start + emoji.length, next.length);
      el?.focus();
      el?.setSelectionRange(caret, caret);
    });
  };

  /** GIFs are standalone messages; keep any text/upload draft intact. */
  const sendGif = async (url: string) => {
    if (sending) return;
    setError(null);
    setSending(true);
    try {
      await sendMessage({ channelId, content: url, replyToId: replyTo?.id });
      onClearReply();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to send GIF");
    } finally {
      setSending(false);
      textarea.current?.focus();
    }
  };

  const menuOpen = mention !== null && matches.length > 0;
  const sendOnEnter = usePrefs((p) => p.sendOnEnter);

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (menuOpen) {
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setActiveIndex((i) => (i + 1) % matches.length);
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        setActiveIndex((i) => (i - 1 + matches.length) % matches.length);
        return;
      }
      if (e.key === "Enter" || e.key === "Tab") {
        e.preventDefault();
        pick(matches[activeIndex]!);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        setMention(null);
        return;
      }
    }
    if (e.key !== "Enter" || e.shiftKey) return;
    // sendOnEnter off ⇒ Enter is a newline and Ctrl/⌘+Enter sends instead.
    if (sendOnEnter || e.ctrlKey || e.metaKey) {
      e.preventDefault();
      void send();
    }
  };

  /** Pasted screenshots arrive as files on the clipboard, same as a pick. */
  const onPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(e.clipboardData.files);
    if (files.length === 0) return;
    e.preventDefault();
    addFiles(files);
  };

  return (
    <div
      className="px-3 pb-3 md:px-4 md:pb-5"
      onDragOver={(e) => {
        if (!e.dataTransfer.types.includes("Files")) return;
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={(e) => {
        // Fires for children too; ignore all but the drag actually leaving.
        if (e.currentTarget.contains(e.relatedTarget as Node | null)) return;
        setDragging(false);
      }}
      onDrop={(e) => {
        if (!e.dataTransfer.types.includes("Files")) return;
        e.preventDefault();
        setDragging(false);
        addFiles(Array.from(e.dataTransfer.files));
      }}
    >
      {replyTo && (
        <div className="flex items-center justify-between rounded-t-xl border border-b-0 border-border bg-surface-1 px-3 py-1.5 text-xs text-ink-secondary">
          <span className="truncate">
            Replying to{" "}
            <span className="font-semibold text-ink">{replyTo.author.displayName}</span>
          </span>
          <button
            type="button"
            aria-label="Cancel reply"
            onClick={onClearReply}
            className="rounded p-0.5 text-ink-muted transition-colors hover:text-ink"
          >
            <X aria-hidden className="size-3.5" />
          </button>
        </div>
      )}

      <div className="relative">
        {menuOpen && (
          <ul className="absolute bottom-full z-20 mb-2 max-h-64 w-full overflow-y-auto rounded-xl border border-border bg-surface-4 p-1 shadow-2xl">
            {matches.map((m, i) => (
              <li key={m.userId}>
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pick(m);
                  }}
                  onMouseEnter={() => setActiveIndex(i)}
                  className={cn(
                    "flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm",
                    i === activeIndex ? "bg-primary-soft text-ink" : "text-ink-secondary",
                  )}
                >
                  <Avatar user={m.user} className="size-6" />
                  <span className="truncate font-medium">{labelOf(m)}</span>
                  <span className="truncate text-xs text-ink-muted">
                    @{m.user.username}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <ComposerAttachments uploads={uploads} onRemove={drop} onToggleSpoiler={toggleSpoiler} />

        <div
          className={cn(
            "flex items-end gap-2 border border-border bg-surface-3 px-3 py-2",
            replyTo || uploads.length > 0 ? "rounded-b-xl" : "rounded-xl",
            dragging && "border-primary bg-primary-soft",
          )}
        >
          <input
            ref={fileInput}
            type="file"
            multiple
            className="hidden"
            onChange={(e) => {
              addFiles(Array.from(e.target.files ?? []));
              // Let the same file be picked again after removing it.
              e.target.value = "";
            }}
          />
          <button
            type="button"
            aria-label="Attach files"
            onClick={() => fileInput.current?.click()}
            className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-1 hover:text-ink"
          >
            <Paperclip aria-hidden className="size-5" />
          </button>
          <ExpressionPicker onEmoji={insertEmoji} onGif={(url) => void sendGif(url)} />
          <textarea
            ref={textarea}
            value={draft}
            maxLength={MAX_LENGTH}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={onKeyDown}
            onPaste={onPaste}
            onClick={syncMention}
            onKeyUp={(e) => {
              if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) syncMention();
            }}
            placeholder={dragging ? "Drop files to attach" : `Message #${channelName}`}
            aria-label={`Message #${channelName}`}
            rows={Math.min(8, draft.split("\n").length)}
            className="max-h-48 flex-1 resize-none bg-transparent py-1 text-base leading-relaxed placeholder:text-ink-muted focus:outline-none md:text-sm"
          />
          <button
            type="button"
            aria-label="Send message"
            disabled={!canSend}
            onClick={() => void send()}
            className="rounded-lg p-2 text-primary transition-colors hover:bg-primary-soft disabled:opacity-40 disabled:hover:bg-transparent"
          >
            <SendHorizontal aria-hidden className="size-5" />
          </button>
        </div>
      </div>

      {error && (
        <p role="alert" className="mt-1 text-xs text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
