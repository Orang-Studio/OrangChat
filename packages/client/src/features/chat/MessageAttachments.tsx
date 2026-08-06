import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Download,
  EyeOff,
  FileWarning,
  Lock,
  Maximize2,
  Paperclip,
  ShieldAlert,
} from 'lucide-react';
import type { Attachment, SealedAttachmentRef } from '@orangchat/shared';
import { Button } from '../../components/ui/Button';
import {
  MAX_INLINE_SEALED,
  blurDataUrl,
  isSealedThumbnail,
  sealedAttachmentsOf,
  sealedObjectUrl,
} from '../e2ee/attachments';
import { formatBytes, formatTime, inlineUrl } from './attachments';
import { AudioPlayer } from './AudioPlayer';
import { ImageLightbox } from './ImageLightbox';
import { ImageContextMenu } from './ImageContextMenu';
import { VideoLightbox } from './VideoLightbox';

/**
 * Attachments on a sent message. Two kinds, told apart by `expiresAt`: local
 * files, which last as long as the message, and OrangMove files (anything over
 * 10MB), which its reaper deletes within the hour.
 *
 * The expiry is shown rather than hidden - a countdown that turns into "expired"
 * is a better answer than an image that silently breaks, and there's no way to
 * renew it from here: OrangMove's whole contract is that files don't persist.
 */

/** Reason for `expiresAt` when the file's already gone. */
const isExpired = (a: Attachment) =>
  a.expiresAt !== undefined &&
  a.expiresAt !== null &&
  new Date(a.expiresAt).getTime() <= Date.now();

function expiryLabel(expiresAt: string): string {
  const remainingMs = new Date(expiresAt).getTime() - Date.now();
  if (remainingMs <= 0) return 'Expired';
  const minutes = Math.round(remainingMs / 60_000);
  if (minutes < 1) return 'Expires in under a minute';
  return `Expires in ${minutes} min`;
}

/** Re-render while a countdown is live so it doesn't sit at a stale number. */
function useExpiryTick(attachments: Attachment[]) {
  const ticking = attachments.some((a) => a.expiresAt && !isExpired(a));
  const [, force] = useState(0);
  useEffect(() => {
    if (!ticking) return;
    const id = setInterval(() => force((n) => n + 1), 30_000);
    return () => clearInterval(id);
  }, [ticking]);
}

function Expired({ attachment }: { attachment: Attachment }) {
  return (
    <div className="flex max-w-sm items-center gap-2 rounded-lg border border-border bg-surface-1 px-3 py-2 text-ink-muted">
      <FileWarning aria-hidden className="size-4 shrink-0" />
      <div className="min-w-0">
        <p className="truncate text-xs font-medium line-through">{attachment.filename}</p>
        <p className="text-[11px]">Expired - large files are only kept for an hour</p>
      </div>
    </div>
  );
}

/**
 * An image omni-moderation judged inappropriate at upload. Keep its pixels out
 * of the page until the viewer explicitly opts in to seeing them.
 */
function Flagged({ attachment }: { attachment: Attachment }) {
  const [revealed, setRevealed] = useState(false);
  if (revealed) return <Body attachment={attachment} />;

  return (
    <div className="flex max-w-sm items-center gap-2 rounded-lg border border-border bg-surface-1 px-3 py-2 text-ink-muted">
      <ShieldAlert aria-hidden className="size-4 shrink-0 text-danger" />
      <div className="min-w-0 flex-1">
        <p className="text-xs font-medium text-ink">Inappropriate content</p>
        <p className="text-[11px]">
          This image was hidden by automatic moderation
          {attachment.filename && ` · ${attachment.filename}`}
        </p>
      </div>
      <Button type="button" size="sm" className="shrink-0" onClick={() => setRevealed(true)}>
        Show
      </Button>
    </div>
  );
}

/**
 * The author's spoiler cover. Blurs what's underneath rather than withholding
 * it - the file is already in the page either way, so this is a courtesy, not a
 * control, and `Flagged` is what's used when it has to actually be one.
 */
function SpoilerShade({ children }: { children: ReactNode }) {
  const [revealed, setRevealed] = useState(false);
  const covered = useRef<HTMLDivElement>(null);

  // The expand/download controls under the blur are still Tab-reachable on
  // aria-hidden alone, which would walk a keyboard user straight past the cover.
  useEffect(() => {
    if (covered.current) covered.current.inert = true;
  }, [revealed]);

  if (revealed) return <>{children}</>;

  return (
    <div className="relative inline-block max-w-full overflow-hidden rounded-lg">
      {/* Scaled up so the blur doesn't wash out into visible edges. */}
      <div ref={covered} aria-hidden className="pointer-events-none scale-105 select-none blur-xl">
        {children}
      </div>
      <button
        type="button"
        onClick={() => setRevealed(true)}
        className="absolute inset-0 grid place-items-center rounded-lg bg-surface-0/30 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        <span className="flex items-center gap-1.5 rounded-full bg-surface-0/85 px-3 py-1 text-[11px] font-semibold uppercase tracking-wide text-ink">
          <EyeOff aria-hidden className="size-3.5" />
          Spoiler
        </span>
      </button>
    </div>
  );
}

function FileCard({ attachment }: { attachment: Attachment }) {
  return (
    <a
      href={attachment.url}
      // The stored name is an opaque id, so the real one has to come from here.
      download={attachment.filename}
      className="flex max-w-sm items-center gap-2 rounded-lg border border-border bg-surface-1 px-3 py-2 transition-colors hover:border-primary"
    >
      <Paperclip aria-hidden className="size-4 shrink-0 text-ink-muted" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-xs font-medium text-ink">{attachment.filename}</p>
        <p className="text-[11px] text-ink-muted">
          {formatBytes(attachment.size)}
          {attachment.expiresAt && ` · ${expiryLabel(attachment.expiresAt)}`}
        </p>
      </div>
      <Download aria-hidden className="size-4 shrink-0 text-ink-muted" />
    </a>
  );
}

export function ImagePreview({ attachment }: { attachment: Attachment }) {
  const [broken, setBroken] = useState(false);
  const [expanded, setExpanded] = useState(false);
  if (broken) return <FileCard attachment={attachment} />;

  return (
    <figure className="max-w-sm">
      {/* A button, not a link: the file's own URL is served as a download, so
          navigating to it is the one thing clicking must not do. */}
      <ImageContextMenu attachment={attachment}>
        <button
          type="button"
          onClick={() => setExpanded(true)}
          onContextMenu={(event) => event.stopPropagation()}
          aria-label={`Expand ${attachment.filename}`}
          className="block cursor-zoom-in rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        >
          <img
            src={attachment.url}
            alt={attachment.filename}
            // Known for local uploads (measured server-side); OrangMove files fall
            // back to intrinsic size, so the box may shift as they load.
            width={attachment.width}
            height={attachment.height}
            loading="lazy"
            onError={() => setBroken(true)}
            className="max-h-80 w-auto rounded-lg border border-border object-contain"
          />
        </button>
      </ImageContextMenu>
      <figcaption className="mt-0.5 flex items-center gap-1 text-[11px] text-ink-muted">
        <span className="truncate">{attachment.filename}</span>
        {attachment.expiresAt && (
          <span className="shrink-0 text-warning">· {expiryLabel(attachment.expiresAt)}</span>
        )}
      </figcaption>
      {/* Mounted only while open so a channel full of images isn't also a
          channel full of idle dialogs. */}
      {expanded && <ImageLightbox attachment={attachment} open onOpenChange={setExpanded} />}
    </figure>
  );
}

export function AudioAttachment({ attachment }: { attachment: Attachment }) {
  const [broken, setBroken] = useState(false);
  if (broken) return <FileCard attachment={attachment} />;

  return (
    <AudioPlayer
      attachment={attachment}
      expiryLabel={attachment.expiresAt ? expiryLabel(attachment.expiresAt) : undefined}
      onError={() => setBroken(true)}
    />
  );
}

export function VideoAttachment({ attachment }: { attachment: Attachment }) {
  const [broken, setBroken] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [startTime, setStartTime] = useState(0);
  const [playing, setPlaying] = useState(false);

  if (broken) return <FileCard attachment={attachment} />;

  const expand = (video: HTMLVideoElement | null) => {
    if (video) {
      setStartTime(video.currentTime);
      video.pause();
    }
    setExpanded(true);
  };

  return (
    <figure className="max-w-sm">
      <div className="group relative inline-flex max-w-full overflow-hidden rounded-lg border border-border bg-black">
        <video
          src={attachment.url}
          // The sender's client captured the first frame at upload, so the box
          // shows the clip without fetching any of it. Old videos fall back to
          // the dark box behind the play button.
          poster={attachment.thumbnailUrl}
          aria-label={attachment.filename}
          width={attachment.width}
          height={attachment.height}
          controls
          controlsList="nodownload"
          playsInline
          // The still does the previewing; the bytes only move on play.
          preload="none"
          onError={() => setBroken(true)}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onDoubleClick={(event) => expand(event.currentTarget)}
          className="max-h-80 max-w-full bg-black object-contain"
        />
        {!playing && attachment.duration !== undefined && (
          <span className="pointer-events-none absolute bottom-1.5 right-1.5 rounded bg-black/70 px-1.5 py-0.5 text-[10px] font-semibold tabular-nums text-white">
            {formatTime(attachment.duration)}
          </span>
        )}
        <button
          type="button"
          onClick={(event) =>
            expand(
              event.currentTarget.parentElement?.querySelector<HTMLVideoElement>('video') ?? null,
            )
          }
          aria-label={`Expand ${attachment.filename}`}
          className="absolute right-2 top-2 grid size-8 place-items-center rounded-md bg-black/65 text-white opacity-90 transition hover:bg-black/80 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary group-hover:opacity-100"
        >
          <Maximize2 aria-hidden className="size-4" />
        </button>
      </div>
      <figcaption className="mt-0.5 flex items-center gap-1 text-[11px] text-ink-muted">
        <span className="truncate">{attachment.filename}</span>
        {attachment.expiresAt && (
          <span className="shrink-0 text-warning">· {expiryLabel(attachment.expiresAt)}</span>
        )}
      </figcaption>
      {expanded && (
        <VideoLightbox
          attachment={attachment}
          open
          startTime={startTime}
          onOpenChange={setExpanded}
        />
      )}
    </figure>
  );
}

function Body({ attachment }: { attachment: Attachment }) {
  // Everything below renders the bytes in place, so it needs the url a media
  // element can actually load - see inlineUrl. FileCard keeps the original,
  // which is the one served as a named download.
  const inline = { ...attachment, url: inlineUrl(attachment) };
  if (attachment.contentType.startsWith('image/')) return <ImagePreview attachment={inline} />;
  if (attachment.contentType.startsWith('audio/')) return <AudioAttachment attachment={inline} />;
  if (attachment.contentType.startsWith('video/')) return <VideoAttachment attachment={inline} />;
  return <FileCard attachment={attachment} />;
}

/**
 * A file whose bytes only this device can read (docs/E2EE.md §7). Nothing in the
 * page can point an `<img>` at it, so it is fetched, decrypted here, and handed
 * on as an object URL - after which every renderer below behaves normally.
 */
function SealedBody({
  attachment,
  sealed,
  all,
}: {
  attachment: Attachment;
  sealed: SealedAttachmentRef;
  all: Attachment[];
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [wanted, setWanted] = useState(sealed.size <= MAX_INLINE_SEALED);
  const isVideo = sealed.contentType.startsWith('video/');

  // A sealed video's poster is its sealed thumbnail row - a small blob, so it
  // decrypts on its own even while the main file stays behind "Decrypt".
  const [sharp, setSharp] = useState<string | null>(null);
  // ...and behind that, the postage stamp carried in the payload: no row, no
  // fetch, no decrypt, so it is on screen immediately and it is still there for
  // the messages whose thumbnail upload never landed.
  const blur = blurDataUrl(sealed.blur) ?? null;
  const poster = sharp ?? blur;
  useEffect(() => {
    if (!isVideo || !sealed.thumb) return;
    const thumbRow = all.find((a) => a.id === sealed.thumb!.attachmentId);
    if (!thumbRow) return;
    let cancelled = false;
    void sealedObjectUrl(sealed.thumb, thumbRow.url)
      .then((resolved) => !cancelled && setSharp(resolved))
      .catch(() => {
        // A poster is a courtesy; the file still opens without it.
      });
    return () => {
      cancelled = true;
    };
  }, [isVideo, sealed, all]);

  useEffect(() => {
    if (!wanted || url || failed) return;
    let cancelled = false;
    void sealedObjectUrl(sealed, attachment.url)
      .then((resolved) => !cancelled && setUrl(resolved))
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [wanted, url, failed, sealed, attachment.url]);

  if (failed) {
    return (
      <div className="flex max-w-sm items-center gap-2 rounded-lg border border-border bg-surface-1 px-3 py-2 text-ink-muted">
        <FileWarning aria-hidden className="size-4 shrink-0" />
        <div className="min-w-0">
          <p className="truncate text-xs font-medium">{sealed.filename}</p>
          <p className="text-[11px]">This file could not be decrypted on this device</p>
        </div>
      </div>
    );
  }

  // Big files stay out of memory until asked for: decryption has one tag over
  // the whole file, so "play inline" means "load all of it".
  if (!wanted) {
    if (poster) {
      return (
        <div className="relative max-w-sm overflow-hidden rounded-lg border border-border">
          {/* The stamp is 24px on its long edge; blowing it up to card width
              is the blur. The extra filter only hides the JPEG's blocking, and
              the scale keeps that filter from feathering the edges. */}
          <img
            src={poster}
            alt={sealed.filename}
            className={sharp ? 'w-full' : 'w-full scale-105 blur-md'}
          />
          <div className="absolute inset-x-0 bottom-0 flex items-center gap-2 bg-gradient-to-t from-black/70 to-transparent px-3 pb-2 pt-10">
            {sealed.duration != null && (
              <span className="rounded bg-black/60 px-1.5 py-0.5 text-[11px] font-medium text-white">
                {formatTime(sealed.duration)}
              </span>
            )}
            <div className="flex-1" />
            <Button type="button" size="sm" onClick={() => setWanted(true)}>
              Decrypt
            </Button>
          </div>
        </div>
      );
    }
    return (
      <div className="flex max-w-sm items-center gap-2 rounded-lg border border-border bg-surface-1 px-3 py-2">
        <Lock aria-hidden className="size-4 shrink-0 text-ink-muted" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs font-medium text-ink">{sealed.filename}</p>
          <p className="text-[11px] text-ink-muted">{formatBytes(sealed.size)} · encrypted</p>
        </div>
        <Button type="button" size="sm" className="shrink-0" onClick={() => setWanted(true)}>
          Decrypt
        </Button>
      </div>
    );
  }

  if (!url) {
    // A big clip decrypts in one pass, so this is not a flash - it is the whole
    // wait. Showing the stamp means the wait is at least visibly about *this*
    // video rather than about a grey bar.
    if (poster) {
      return (
        <div className="relative max-w-sm overflow-hidden rounded-lg border border-border">
          <img
            src={poster}
            alt={sealed.filename}
            className={sharp ? 'w-full' : 'w-full scale-105 blur-md'}
          />
          <div className="absolute inset-0 flex items-center justify-center bg-black/30">
            <Lock aria-hidden className="size-5 text-white/90" />
          </div>
        </div>
      );
    }
    return (
      <div className="flex max-w-sm items-center gap-2 rounded-lg border border-border bg-surface-1 px-3 py-2 text-ink-muted">
        <Lock aria-hidden className="size-4 shrink-0" />
        <p className="truncate text-xs">Decrypting {sealed.filename}…</p>
      </div>
    );
  }

  return (
    <Body
      attachment={{
        ...attachment,
        url,
        ...(poster ? { thumbnailUrl: poster } : {}),
        ...(sealed.duration != null ? { duration: sealed.duration } : {}),
      }}
    />
  );
}

function Resolved({ attachment, all }: { attachment: Attachment; all: Attachment[] }) {
  const sealed = sealedAttachmentsOf(attachment.id);
  return sealed ? (
    <SealedBody attachment={attachment} sealed={sealed} all={all} />
  ) : (
    <Body attachment={attachment} />
  );
}

export function MessageAttachments({ attachments }: { attachments: Attachment[] }) {
  useExpiryTick(attachments);
  const visible = attachments.filter((attachment) => !isSealedThumbnail(attachment.id));
  if (visible.length === 0) return null;

  return (
    <ul className="mt-1.5 flex flex-col gap-1.5">
      {visible.map((a) => (
        <li key={a.id}>
          {/* Moderation outranks everything else until the viewer explicitly
              chooses to reveal the image. */}
          {a.flagged ? (
            <Flagged attachment={a} />
          ) : isExpired(a) ? (
            <Expired attachment={a} />
          ) : a.spoiler ? (
            <SpoilerShade>
              <Resolved attachment={a} all={attachments} />
            </SpoilerShade>
          ) : (
            <Resolved attachment={a} all={attachments} />
          )}
        </li>
      ))}
    </ul>
  );
}
