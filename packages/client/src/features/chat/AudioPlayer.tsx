import { useEffect, useRef, useState } from 'react';
import { Download, Pause, Play } from 'lucide-react';
import type { Attachment } from '@orangchat/shared';
import { formatBytes, formatTime } from './attachments';

/**
 * Inline player for an audio attachment.
 *
 * Custom controls rather than `<audio controls>`: the native widget is a
 * different size, font, and colour in every browser, and it can't carry the
 * filename or expiry that every other attachment shows.
 *
 * `Content-Disposition: attachment` on the served file doesn't apply to media
 * elements - only to navigations - so this plays without loosening nginx.
 */

/**
 * The element that's currently playing, if any. Two clips talking over each
 * other is never what was meant, and the browser won't arbitrate on its own.
 */
let playing: HTMLAudioElement | null = null;

export function AudioPlayer({
  attachment,
  expiryLabel,
  onError,
}: {
  attachment: Attachment;
  /** Rendered under the scrubber when the file is on OrangMove. */
  expiryLabel?: string;
  /** Fall back to a plain file card - the browser can't decode this one. */
  onError: () => void;
}) {
  const ref = useRef<HTMLAudioElement>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [current, setCurrent] = useState(0);
  // The sender measured the length at upload; show it before the file's own
  // headers arrive rather than sitting at "0:00" until play is pressed.
  const [duration, setDuration] = useState(attachment.duration ?? 0);

  // Switching channels shouldn't leave a clip running out of sight. Browsers do
  // pause a media element once it's out of the document, but only after a trip
  // through a stable state - and an element left in `playing` would meanwhile
  // block the next clip from starting.
  useEffect(() => {
    const el = ref.current;
    return () => {
      if (playing === el) playing = null;
      el?.pause();
    };
  }, []);

  const toggle = () => {
    const el = ref.current;
    if (!el) return;
    if (el.paused) {
      if (playing && playing !== el) playing.pause();
      playing = el;
      void el.play().catch(onError);
    } else {
      el.pause();
    }
  };

  const seek = (to: number) => {
    const el = ref.current;
    if (!el) return;
    el.currentTime = to;
    setCurrent(to);
  };

  // Streamed files can report Infinity until fully buffered; a scrubber with no
  // known end is worse than none, so it stays disabled rather than lying.
  const seekable = duration > 0 && Number.isFinite(duration);

  return (
    <div className="flex max-w-sm items-center gap-2.5 rounded-lg border border-border bg-surface-1 px-3 py-2">
      <audio
        ref={ref}
        src={attachment.url}
        preload="metadata"
        onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)}
        onDurationChange={(e) => setDuration(e.currentTarget.duration)}
        onTimeUpdate={(e) => setCurrent(e.currentTarget.currentTime)}
        onPlay={() => setIsPlaying(true)}
        onPause={() => setIsPlaying(false)}
        onEnded={() => {
          setIsPlaying(false);
          setCurrent(0);
        }}
        onError={onError}
      />

      <button
        type="button"
        onClick={toggle}
        aria-label={`${isPlaying ? 'Pause' : 'Play'} ${attachment.filename}`}
        className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary text-ink-on-primary transition-colors hover:bg-primary-hover"
      >
        {isPlaying ? (
          <Pause aria-hidden className="size-3.5 fill-current" />
        ) : (
          // Nudged right so the triangle reads as centred in the circle.
          <Play aria-hidden className="size-3.5 translate-x-px fill-current" />
        )}
      </button>

      <div className="min-w-0 flex-1">
        <p className="truncate text-xs font-medium text-ink">{attachment.filename}</p>
        <input
          type="range"
          min={0}
          max={seekable ? duration : 0}
          step={0.1}
          value={current}
          disabled={!seekable}
          onChange={(e) => seek(Number(e.target.value))}
          aria-label={`Seek ${attachment.filename}`}
          className="mt-1 w-full accent-[var(--oc-primary)]"
        />
        <p className="mt-0.5 flex gap-1 text-[11px] text-ink-muted">
          <span>
            {formatTime(current)}
            {duration > 0 && ` / ${formatTime(duration)}`}
          </span>
          {attachment.size > 0 && <span>· {formatBytes(attachment.size)}</span>}
          {expiryLabel && <span className="truncate text-warning">· {expiryLabel}</span>}
        </p>
      </div>

      <a
        href={attachment.url}
        // The stored name is an opaque id, so the real one comes from here.
        download={attachment.filename}
        aria-label={`Download ${attachment.filename}`}
        className="shrink-0 rounded-md p-1 text-ink-muted transition-colors hover:text-ink"
      >
        <Download aria-hidden className="size-4" />
      </a>
    </div>
  );
}
