import { useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Camera, Mic, Paperclip, SendHorizontal, Trash2, X } from 'lucide-react';
import type { Message, ServerMember } from '@orangchat/shared';
import { cn } from '../../lib/cn';
import { usePrefs } from '../../lib/prefs';
import { Avatar } from '../../components/Avatar';
import { Dialog, DialogContent } from '../../components/ui/Dialog';
import { Button } from '../../components/ui/Button';
import { emitTyping, sendMessage } from './socket-actions';
import {
  isEphemeral,
  MAX_PER_MESSAGE,
  uploadAttachment,
  uploadSealedAttachment,
} from './attachments';
import { isEncrypted } from '../e2ee/store';
import { ComposerAttachments, isSettled, type PendingUpload } from './ComposerAttachments';
import { ExpressionPicker } from './ExpressionPicker';
import { emojiForShortcode } from './emoji-search';
import { highlightEmoji } from './EmojiHighlight';
import { normalizeCustomEmojiNames, useEmojiMap } from '../emojis/queries';
import { clearDraft, loadDraft, saveDraft, saveDraftNow } from './drafts';
import { t } from "../../lib/i18n";


const TYPING_THROTTLE_MS = 4_000;
const MAX_LENGTH = 4_000;

const LENGTH_WARNING_THRESHOLD = MAX_LENGTH - 400;
const MENTION_LIMIT = 8;


const VOICE_SWIPE_PX = 72;


const VOICE_MIN_HOLD_MS = 600;

function recordingMimeType(): string | undefined {
  if (typeof MediaRecorder === 'undefined') return undefined;
  return ['audio/ogg;codecs=opus', 'audio/webm;codecs=opus', 'audio/mp4'].find((type) =>
    MediaRecorder.isTypeSupported(type),
  );
}

type PastedTokenKind = 'login' | 'bot';


function findPastedTokenKind(text: string): PastedTokenKind | null {
  if (/\b[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b/i.test(text)) {
    return 'login';
  }
  if (/\b[A-Za-z0-9_-]{6,24}\.[A-Za-z0-9_-]{32,80}\b/.test(text)) {
    return 'bot';
  }
  return null;
}

function recordingExtension(mimeType: string): string {
  if (mimeType.startsWith('audio/ogg')) return 'ogg';
  if (mimeType.startsWith('audio/mp4')) return 'm4a';
  return 'weba';
}

function formatRecordingTime(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`;
}

interface ComposerProps {
  channelId: string;
  channelName: string;
  replyTo: Message | null;
  onClearReply: () => void;
  /** Members for @mention autocomplete (empty for DMs). */
  members?: ServerMember[];
}

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
  const emojiMap = useEmojiMap();
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [uploads, setUploads] = useState<PendingUpload[]>([]);
  const [dragging, setDragging] = useState(false);
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [tokenWarning, setTokenWarning] = useState<{
    kind: PastedTokenKind;
    text: string;
  } | null>(null);
  const lastTypingSent = useRef(0);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const mirror = useRef<HTMLDivElement>(null);
  // A paste that was cut to fit MAX_LENGTH deserves a word, not silence.
  const [truncatedNotice, setTruncatedNotice] = useState(false);
  const truncateTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const cameraInput = useRef<HTMLInputElement>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const recordingStream = useRef<MediaStream | null>(null);
  const recordingChunks = useRef<Blob[]>([]);
  const recordingCancelled = useRef(false);
  const recordingStartedAt = useRef(0);
  const [recording, setRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  // A locked recording keeps going with nothing held down, so it is driven by
  // the stop and delete buttons instead of by the pointer.
  const [recordingLocked, setRecordingLocked] = useState(false);
  /** How far the holding pointer has travelled, in px; drives the swipe hints. */
  const [recordingDrag, setRecordingDrag] = useState(0);
  /** The live hold, or null once it has been released, locked or discarded. */
  const hold = useRef<{ x: number; startedAt: number } | null>(null);
  // Uploads outlive the render that started them; a ref keeps cleanup honest
  // even when the component unmounts mid-flight.
  const live = useRef<PendingUpload[]>([]);
  live.current = uploads;
  // Latest draft, so the channel-switch cleanup can persist what's in the box.
  const draftRef = useRef('');
  draftRef.current = draft;

  useEffect(() => {
    if (!recording) return;
    const timer = window.setInterval(() => {
      setRecordingSeconds(Math.floor((Date.now() - recordingStartedAt.current) / 1000));
    }, 250);
    return () => window.clearInterval(timer);
  }, [recording]);

  useEffect(() => {
    textarea.current?.focus();
  }, [replyTo, channelId]);

  useEffect(() => () => {
    if (truncateTimer.current) clearTimeout(truncateTimer.current);
  }, []);

  // Load this channel's saved draft on open; persist it on the way out.
  useEffect(() => {
    setMention(null);
    setDraft('');
    // Throttle windows are per-conversation; carrying one across a channel
    // switch would swallow the first keystroke in the new channel.
    lastTypingSent.current = 0;
    let cancelled = false;
    void loadDraft(channelId).then((text) => {
      // don't clobber anything typed while the load was in flight.
      if (!cancelled && draftRef.current === '') setDraft(text);
    });
    return () => {
      cancelled = true;
      saveDraftNow(channelId, draftRef.current.trim());
    };
  }, [channelId]);

  // A draft belongs to its channel: switching away (or leaving) cancels uploads
  // in flight rather than silently attaching them to wherever you land next.
  useEffect(
    () => () => {
      recordingCancelled.current = true;
      if (recorder.current && recorder.current.state !== 'inactive') recorder.current.stop();
      recordingStream.current?.getTracks().forEach((track) => track.stop());
      recorder.current = null;
      recordingStream.current = null;
      hold.current = null;
      setRecording(false);
      setRecordingLocked(false);
      setRecordingDrag(0);
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
        (m) => labelOf(m).toLowerCase().includes(q) || m.user.username.toLowerCase().includes(q),
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
    saveDraft(channelId, value.trim());
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
    const handle = m.user.username;
    const el = textarea.current;
    const caret = el?.selectionStart ?? draft.length;
    const next = `${draft.slice(0, mention.start)}@${handle} ${draft.slice(caret)}`;
    setDraft(next);
    setMention(null);
    requestAnimationFrame(() => {
      const pos = mention.start + handle.length + 2;
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
        preview: file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined,
        abort: () => controller.abort(),
      };
      setUploads((prev) => [...prev, entry]);

      const handle = {
        signal: controller.signal,
        onProgress: (fraction: number) => patch(key, { progress: fraction }),
      };
      // In an encrypted conversation the bytes, the name and the type are sealed
      // before anything leaves the machine, so the upload the server sees is an
      // opaque blob and a length (§7).
      const upload = isEncrypted(channelId)
        ? uploadSealedAttachment(file, handle).then(({ attachment, supportingAttachments, ref }) =>
            patch(key, { attachment, supportingAttachments, sealed: ref, progress: 1 }),
          )
        : uploadAttachment(file, handle).then((attachment) =>
            patch(key, { attachment, progress: 1 }),
          );

      void upload.catch((err: unknown) => {
        // Cancelling is the user's own doing - drop(key) already removed the
        // entry, so there's nothing to report.
        if (err instanceof DOMException && err.name === 'AbortError') return;
        patch(key, { error: err instanceof Error ? err.message : 'Upload failed' });
      });
    }
  };

  const startRecording = async () => {
    if (recording || sending) return;
    const mimeType = recordingMimeType();
    if (!mimeType || !navigator.mediaDevices?.getUserMedia) {
      setError('Voice recording is not supported by this browser');
      return;
    }
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const next = new MediaRecorder(stream, { mimeType });
      recordingChunks.current = [];
      recordingCancelled.current = false;
      recordingStartedAt.current = Date.now();
      recordingStream.current = stream;
      recorder.current = next;
      next.ondataavailable = (event) => {
        if (event.data.size > 0) recordingChunks.current.push(event.data);
      };
      next.onstop = () => {
        const chunks = recordingChunks.current;
        const cancelled = recordingCancelled.current;
        const actualMimeType = next.mimeType || mimeType;
        stream.getTracks().forEach((track) => track.stop());
        if (!cancelled && chunks.length > 0) {
          const blob = new Blob(chunks, { type: actualMimeType });
          const name = `voice-${Date.now()}.${recordingExtension(actualMimeType)}`;
          addFiles([new File([blob], name, { type: actualMimeType })]);
        }
        recordingChunks.current = [];
        recordingStream.current = null;
        recorder.current = null;
        setRecording(false);
      };
      next.onerror = () => {
        recordingCancelled.current = true;
        setError('Voice recording failed');
      };
      next.start(250);
      setRecordingSeconds(0);
      setRecording(true);
      // The mic can take a while to open - a permission prompt, a slow device -
      // and by then the press may be over. Rather than lose the recording,
      // hand it to the buttons, which is where a pointer-less recording belongs.
      if (!hold.current) setRecordingLocked(true);
    } catch (err) {
      recordingStream.current?.getTracks().forEach((track) => track.stop());
      recordingStream.current = null;
      recorder.current = null;
      setError(
        err instanceof DOMException && err.name === 'NotAllowedError'
          ? 'Microphone access is required to record a voice message'
          : 'Could not start voice recording',
      );
    }
  };

  const finishRecording = (cancel: boolean) => {
    const active = recorder.current;
    hold.current = null;
    setRecordingLocked(false);
    setRecordingDrag(0);
    if (!active) return;
    recordingCancelled.current = cancel;
    if (active.state === 'inactive') {
      recordingStream.current?.getTracks().forEach((track) => track.stop());
      recordingStream.current = null;
      recorder.current = null;
      setRecording(false);
    } else {
      active.stop();
    }
  };

  // ── Hold to record ──────────────────────────────────────
  //
  // Press and hold the mic, let go to send. Swipe left to hand the recording to
  // the buttons and get your finger back; swipe right to throw it away. Keyboard
  // users get the locked form directly - a control that only works while held is
  // no control at all without a pointer.

  const beginHold = (clientX: number) => {
    if (recording || sending) return;
    hold.current = { x: clientX, startedAt: Date.now() };
    setRecordingLocked(false);
    setRecordingDrag(0);
    void startRecording();
  };

  const moveHold = (clientX: number) => {
    const active = hold.current;
    if (!active || recordingLocked) return;
    const dx = clientX - active.x;
    setRecordingDrag(dx);
    if (dx <= -VOICE_SWIPE_PX) {
      hold.current = null;
      setRecordingDrag(0);
      setRecordingLocked(true);
    } else if (dx >= VOICE_SWIPE_PX) {
      finishRecording(true);
    }
  };

  const endHold = () => {
    const active = hold.current;
    if (!active) return;
    hold.current = null;
    setRecordingDrag(0);
    // Too brief to hear, and almost always a click by somebody who expected the
    // old press-to-start button. Say what the mic wants instead of sending it.
    if (Date.now() - active.startedAt < VOICE_MIN_HOLD_MS) {
      finishRecording(true);
      setError('Hold the mic to record, then let go to send');
      return;
    }
    finishRecording(false);
  };

  const knownEmojiName = useMemo(() => {
    const custom = new Set(Object.values(emojiMap).map((e) => e.name.toLowerCase()));
    return (name: string) => custom.has(name) || emojiForShortcode(name) !== undefined;
  }, [emojiMap]);
  const highlighted = useMemo(
    () => highlightEmoji(draft, knownEmojiName),
    [draft, knownEmojiName],
  );

  const uploading = uploads.some((u) => !isSettled(u));
  const failed = uploads.filter((u) => u.error !== undefined);
  const ready = uploads.filter((u) => u.attachment !== undefined);
  // Attachments can carry a message on their own, so an empty draft is fine as
  // long as something is going with it.
  const canSend = (draft.trim().length > 0 || ready.length > 0) && !sending && !uploading;

  const send = async () => {
    if (!canSend) return;
    if (failed.length > 0) {
      setError('Remove the attachments that failed to upload first');
      return;
    }
    const content = normalizeCustomEmojiNames(draft.trim(), emojiMap);
    setSending(true);
    try {
      await sendMessage({
        channelId,
        content,
        replyToId: replyTo?.id,
        attachmentIds: ready.flatMap((u) => [
          u.attachment!.id,
          ...(u.supportingAttachments ?? []).map((attachment) => attachment.id),
        ]),
        spoilerAttachmentIds: ready.filter((u) => u.spoiler).map((u) => u.attachment!.id),
        optimisticAttachments: ready.flatMap((u) => [
          u.attachment!,
          ...(u.supportingAttachments ?? []),
        ]),
        sealedAttachments: ready
          .filter((u) => u.sealed)
          .map((u) => ({ ...u.sealed!, ...(u.spoiler ? { spoiler: true } : {}) })),
      });
      setDraft('');
      clearDraft(channelId);
      // The sent message clears the indicator on every receiver, so the next
      // keystroke has to be treated as a fresh start rather than falling inside
      // the window this send happened to land in.
      lastTypingSent.current = 0;
      for (const u of ready) if (u.preview) URL.revokeObjectURL(u.preview);
      setUploads([]);
      onClearReply();
    } catch (err) {
      // The uploads survive a failed send - they're still staged server-side, so
      // the same ids work on a retry.
      setError(err instanceof Error ? err.message : 'Failed to send');
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
      setError(err instanceof Error ? err.message : 'Failed to send GIF');
    } finally {
      setSending(false);
      textarea.current?.focus();
    }
  };

  const menuOpen = mention !== null && matches.length > 0;
  const sendOnEnter = usePrefs((p) => p.sendOnEnter);

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (menuOpen) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActiveIndex((i) => (i + 1) % matches.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActiveIndex((i) => (i - 1 + matches.length) % matches.length);
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        pick(matches[activeIndex]!);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setMention(null);
        return;
      }
    }
    if (e.key !== 'Enter' || e.shiftKey) return;
    // sendOnEnter off ⇒ Enter is a newline and Ctrl/⌘+Enter sends instead.
    if (sendOnEnter || e.ctrlKey || e.metaKey) {
      e.preventDefault();
      void send();
    }
  };

  /** Pasted screenshots arrive as files on the clipboard, same as a pick. */
  const onPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(e.clipboardData.files);
    if (files.length === 0) {
      // A pasted login or bot token is not an upload; warn before it lands in
      // the box, where a stray Enter would post it to the conversation.
      const text = e.clipboardData.getData('text/plain');
      if (text.trim()) {
        const kind = findPastedTokenKind(text);
        if (kind) {
          e.preventDefault();
          setTokenWarning({ kind, text });
          return;
        }
      }
      // Anything else goes in natively - where maxLength cuts a long paste
      // with no word about it. Flag the cut the same way the token path does.
      if (text.length > 0) {
        const el = textarea.current;
        const start = el?.selectionStart ?? draft.length;
        const end = el?.selectionEnd ?? start;
        if (draft.length - (end - start) + text.length > MAX_LENGTH) {
          setTruncatedNotice(true);
          if (truncateTimer.current) clearTimeout(truncateTimer.current);
          truncateTimer.current = setTimeout(() => setTruncatedNotice(false), 4000);
        }
      }
      return;
    }
    e.preventDefault();
    addFiles(files);
  };

  const insertPastedText = (text: string) => {
    const el = textarea.current;
    const start = el?.selectionStart ?? draft.length;
    const end = el?.selectionEnd ?? start;
    const full = `${draft.slice(0, start)}${text}${draft.slice(end)}`;
    if (full.length > MAX_LENGTH) {
      setTruncatedNotice(true);
      if (truncateTimer.current) clearTimeout(truncateTimer.current);
      truncateTimer.current = setTimeout(() => setTruncatedNotice(false), 4000);
    }
    const next = full.slice(0, MAX_LENGTH);
    onChange(next);
    requestAnimationFrame(() => {
      const caret = Math.min(start + text.length, next.length);
      el?.focus();
      el?.setSelectionRange(caret, caret);
    });
  };

  return (
    <div
      className="px-3 pb-3 md:px-4 md:pb-5"
      onDragOver={(e) => {
        if (!e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={(e) => {
        // Fires for children too; ignore all but the drag actually leaving.
        if (e.currentTarget.contains(e.relatedTarget as Node | null)) return;
        setDragging(false);
      }}
      onDrop={(e) => {
        if (!e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
        setDragging(false);
        addFiles(Array.from(e.dataTransfer.files));
      }}
    >
      {replyTo && (
        <div className="flex items-center justify-between rounded-t-xl border border-b-0 border-border bg-surface-1 px-3 py-1.5 text-xs text-ink-secondary">
          <span className="truncate">
            {t("composer.replyingTo")} <span className="font-semibold text-ink">{replyTo.author.displayName}</span>
          </span>
          <button
            type="button"
            aria-label={t("composer.cancelReply")}
            onClick={onClearReply}
            className="rounded-lg p-2.5 text-ink-muted transition-colors hover:text-ink"
          >
            <X aria-hidden className="size-5" />
          </button>
        </div>
      )}

      <div className="relative">
        {menuOpen && (
          <ul
            id="oc-mention-listbox"
            role="listbox"
            aria-label={t("composer.mentionSuggestions")}
            className="absolute bottom-full z-20 mb-2 max-h-64 w-full overflow-y-auto rounded-xl border border-border bg-surface-4 p-1 shadow-2xl"
          >
            {matches.map((m, i) => (
              <li key={m.userId} role="option" id={`oc-mention-option-${i}`} aria-selected={i === activeIndex}>
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pick(m);
                  }}
                  onMouseEnter={() => setActiveIndex(i)}
                  className={cn(
                    'flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm',
                    i === activeIndex ? 'bg-primary-soft text-ink' : 'text-ink-secondary',
                  )}
                >
                  <Avatar user={m.user} className="size-6" />
                  <span className="truncate font-medium">{labelOf(m)}</span>
                  <span className="truncate text-xs text-ink-muted">@{m.user.username}</span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <ComposerAttachments uploads={uploads} onRemove={drop} onToggleSpoiler={toggleSpoiler} />

        <div
          className={cn(
            'flex items-end gap-2 border border-border bg-surface-3 px-3 py-2',
            replyTo || uploads.length > 0 ? 'rounded-b-xl' : 'rounded-xl',
            dragging && 'border-primary bg-primary-soft',
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
              e.target.value = '';
            }}
          />
          <input
            ref={cameraInput}
            type="file"
            accept="image/*"
            capture="environment"
            className="hidden"
            onChange={(e) => {
              addFiles(Array.from(e.target.files ?? []));
              e.target.value = '';
            }}
          />
          {!recording && (
            <>
              <button
                type="button"
                aria-label={t("composer.attachFiles")}
                onClick={() => fileInput.current?.click()}
                className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-1 hover:text-ink"
              >
                <Paperclip aria-hidden className="size-5" />
              </button>
              <button
                type="button"
                aria-label={t("composer.takeAPicture")}
                onClick={() => cameraInput.current?.click()}
                className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-1 hover:text-ink"
              >
                <Camera aria-hidden className="size-5" />
              </button>
              <ExpressionPicker onEmoji={insertEmoji} onGif={(url) => void sendGif(url)} />
            </>
          )}
          {recording ? (
            <div className="flex min-w-0 flex-1 items-center gap-3 py-1 text-sm text-ink-secondary">
              <span className="size-2 shrink-0 animate-pulse rounded-full bg-danger" aria-hidden />
              <span>{formatRecordingTime(recordingSeconds)}</span>
              {/* The hint has to say which way does what while the pointer is
                  still down: a swipe with no label is a gesture nobody finds.
                  Each half lights up as its own threshold comes into reach. */}
              {recordingLocked ? (
                <span className="text-xs text-ink-muted">{t("composer.lockedSendWhenYoureDone")}</span>
              ) : (
                <span className="flex items-center gap-3 text-xs">
                  <span
                    className={
                      recordingDrag <= -VOICE_SWIPE_PX / 2 ? 'text-primary' : 'text-ink-muted'
                    }
                  >
                    &lsaquo; lock
                  </span>
                  <span
                    className={
                      recordingDrag >= VOICE_SWIPE_PX / 2 ? 'text-danger' : 'text-ink-muted'
                    }
                  >
                    delete &rsaquo;
                  </span>
                </span>
              )}
            </div>
          ) : (
            <div className="relative min-w-0 flex-1">
              {/* The box paints its own text so shortcodes can stand out; the
                  textarea above it keeps only the caret and the selection. */}
              <div
                ref={mirror}
                aria-hidden
                className="pointer-events-none absolute inset-0 overflow-hidden whitespace-pre-wrap break-words py-1 text-base leading-relaxed text-ink md:text-sm"
              >
                {highlighted}
              </div>
              <textarea
                ref={textarea}
                value={draft}
                maxLength={MAX_LENGTH}
                role="combobox"
                aria-expanded={menuOpen}
                aria-controls={menuOpen ? 'oc-mention-listbox' : undefined}
                aria-autocomplete="list"
                aria-activedescendant={
                  menuOpen ? `oc-mention-option-${activeIndex}` : undefined
                }
                onChange={(e) => onChange(e.target.value)}
                onKeyDown={onKeyDown}
                onPaste={onPaste}
                onClick={syncMention}
                onKeyUp={(e) => {
                  if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) syncMention();
                }}
                placeholder={dragging ? 'Drop files to attach' : `Message #${channelName}`}
                aria-label={`Message #${channelName}`}
                rows={Math.min(8, draft.split('\n').length)}
                onScroll={(e) => {
                  if (mirror.current) mirror.current.scrollTop = e.currentTarget.scrollTop;
                }}
                className="relative block max-h-48 w-full resize-none bg-transparent py-1 text-base leading-relaxed text-transparent caret-ink selection:bg-primary/35 placeholder:text-ink-muted focus:outline-none md:text-sm"
              />
            </div>
          )}
          {recording && recordingLocked ? (
            <>
              <button
                type="button"
                aria-label={t("composer.deleteRecording")}
                onClick={() => finishRecording(true)}
                className="rounded-lg p-2 text-danger transition-colors hover:bg-danger/10"
              >
                <Trash2 aria-hidden className="size-5" />
              </button>
              <button
                type="button"
                aria-label={t("composer.sendVoiceMessage")}
                onClick={() => finishRecording(false)}
                className="rounded-lg p-2 text-primary transition-colors hover:bg-primary-soft"
              >
                <SendHorizontal aria-hidden className="size-5" />
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                aria-label={t("composer.holdToRecordAVoiceMessage")}
                // Keyboard has no hold, so it starts the locked form outright -
                // the same one the stop and delete buttons already drive.
                onKeyDown={(e) => {
                  if (e.key !== 'Enter' && e.key !== ' ') return;
                  e.preventDefault();
                  if (!recording) {
                    setRecordingLocked(true);
                    void startRecording();
                  }
                }}
                onPointerDown={(e) => {
                  if (e.pointerType === 'mouse' && e.button !== 0) return;
                  e.currentTarget.setPointerCapture(e.pointerId);
                  beginHold(e.clientX);
                }}
                onPointerMove={(e) => moveHold(e.clientX)}
                onPointerUp={endHold}
                onPointerCancel={() => finishRecording(true)}
                className={cn(
                  'touch-none rounded-lg p-2 transition-colors',
                  recording ? 'text-danger' : 'text-ink-muted hover:bg-surface-1 hover:text-ink',
                )}
              >
                <Mic aria-hidden className={recording ? 'size-6' : 'size-5'} />
              </button>
              {!recording && (
                <button
                  type="button"
                  aria-label={t("composer.sendMessage")}
                  disabled={!canSend}
                  onClick={() => void send()}
                  className="rounded-lg p-2 text-primary transition-colors hover:bg-primary-soft disabled:opacity-40 disabled:hover:bg-transparent"
                >
                  <SendHorizontal aria-hidden className="size-5" />
                </button>
              )}
            </>
          )}
          {/* The truncation notice is worth announcing once; the running count
              below it is not - at one key per character near the limit, a
              polite live region would read out every single keystroke. */}
          {truncatedNotice && (
            <p role="status" className="shrink-0 text-[10px] font-medium text-warning">
              {t("composer.pasteCutOff", { max: MAX_LENGTH.toLocaleString() })}
            </p>
          )}
          {!truncatedNotice && draft.length >= LENGTH_WARNING_THRESHOLD && (
            <p
              aria-live="off"
              className={cn(
                'shrink-0 text-[10px] tabular-nums',
                draft.length >= MAX_LENGTH ? 'font-semibold text-danger' : 'text-ink-muted',
              )}
            >
              {draft.length.toLocaleString()}/{MAX_LENGTH.toLocaleString()}
            </p>
          )}
        </div>
      </div>

      {error && (
        <p role="alert" className="mt-1 text-xs text-danger">
          {error}
        </p>
      )}

      {tokenWarning && (
        <Dialog open onOpenChange={(open) => !open && setTokenWarning(null)}>
          <DialogContent
            title={
              tokenWarning.kind === 'login'
                ? 'That looks like a login token'
                : 'That looks like a bot token'
            }
          >
            <div className="flex items-start gap-3 rounded-xl border border-warning/30 bg-warning/10 p-3">
              <AlertTriangle aria-hidden className="mt-0.5 size-5 shrink-0 text-warning" />
              <p className="text-sm text-ink-secondary">
                {tokenWarning.kind === 'login' ? (
                  <>
                    {t("composer.aLoginTokenLetsAnyoneWho")}
                  </>
                ) : (
                  <>
                    {t("composer.aBotTokenIsThePassword")}
                  </>
                )}
              </p>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <Button type="button" variant="ghost" onClick={() => setTokenWarning(null)}>
                {t("composer.dontPaste")}
              </Button>
              <Button
                type="button"
                onClick={() => {
                  insertPastedText(tokenWarning.text);
                  setTokenWarning(null);
                }}
              >
                {t("composer.pasteItAnyway")}
              </Button>
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
