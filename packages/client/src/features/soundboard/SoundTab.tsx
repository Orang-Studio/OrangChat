import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Play, Trash2, Upload } from "lucide-react";
import type { Server, Sound } from "@orangchat/shared";
import {
  MAX_SOUND_BYTES,
  MAX_SOUND_SECS,
  deleteSound,
  updateSound,
  uploadSound,
} from "./api";
import { soundKeys, useSounds } from "./queries";

function nameFromFile(file: File): string {
  const base = file.name.replace(/\.[^.]+$/, "").trim();
  return base.slice(0, 32) || "sound";
}

/**
 * Measure the clip in the browser so an over-long file is refused before it is
 * uploaded. The server measures it again and is the one that decides — this is
 * only here to save the round trip.
 *
 * Resolves null when the browser cannot decode it: that is not a verdict, and
 * the server is better placed to give one.
 */
function probeDuration(file: File): Promise<number | null> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file);
    const audio = new Audio();
    const done = (value: number | null) => {
      URL.revokeObjectURL(url);
      resolve(value);
    };
    audio.addEventListener("loadedmetadata", () =>
      done(Number.isFinite(audio.duration) ? audio.duration : null),
    );
    audio.addEventListener("error", () => done(null));
    audio.src = url;
  });
}

function SoundRow({ server, sound }: { server: Server; sound: Sound }) {
  const client = useQueryClient();
  const [name, setName] = useState(sound.name);
  const [error, setError] = useState<string | null>(null);

  const invalidate = () =>
    void client.invalidateQueries({ queryKey: soundKeys.server(server.id) });

  const save = useMutation({
    mutationFn: (patch: { name?: string; volume?: number }) =>
      updateSound(server.id, sound.id, patch),
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: (err: Error) => {
      setName(sound.name);
      setError(err.message);
    },
  });

  const remove = useMutation({
    mutationFn: () => deleteSound(server.id, sound.id),
    onSuccess: invalidate,
    onError: (err: Error) => setError(err.message),
  });

  return (
    <li className="flex items-center gap-3 rounded-lg border border-border bg-surface-2 p-2">
      <button
        type="button"
        // A local preview, not a broadcast: auditioning a clip should not fire
        // it at everyone in the channel.
        onClick={() => {
          const audio = new Audio(sound.url);
          audio.volume = sound.volume;
          void audio.play().catch(() => {});
        }}
        aria-label={`Preview ${sound.name}`}
        title={`Preview ${sound.name}`}
        className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-3 text-ink-secondary transition-colors hover:text-ink"
      >
        <span aria-hidden className="text-base leading-none">
          {sound.emoji ?? <Play className="size-4" />}
        </span>
      </button>
      <div className="min-w-0 flex-1">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          onBlur={() => name !== sound.name && save.mutate({ name })}
          onKeyDown={(e) => e.key === "Enter" && e.currentTarget.blur()}
          aria-label={`Rename ${sound.name}`}
          className="w-full rounded border border-transparent bg-transparent px-1 py-0.5 text-sm outline-none hover:border-border focus:border-primary"
        />
        {error ? (
          <p role="alert" className="px-1 text-xs text-danger">
            {error}
          </p>
        ) : (
          <p className="px-1 text-xs text-ink-muted">{sound.duration.toFixed(1)}s</p>
        )}
      </div>
      <label className="flex shrink-0 items-center gap-1.5 text-xs text-ink-muted">
        <span className="sr-only">Volume for {sound.name}</span>
        <input
          type="range"
          min={0}
          max={1}
          step={0.05}
          defaultValue={sound.volume}
          // Commit on release: dragging fires continuously and each tick would
          // be its own PATCH.
          onPointerUp={(e) => save.mutate({ volume: Number(e.currentTarget.value) })}
          onKeyUp={(e) => save.mutate({ volume: Number(e.currentTarget.value) })}
          className="w-20 accent-primary"
        />
      </label>
      <button
        type="button"
        onClick={() => remove.mutate()}
        disabled={remove.isPending}
        aria-label={`Delete ${sound.name}`}
        title={`Delete ${sound.name}`}
        className="shrink-0 rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-3 hover:text-danger disabled:opacity-50"
      >
        <Trash2 aria-hidden className="size-4" />
      </button>
    </li>
  );
}

export function SoundTab({ server }: { server: Server }) {
  const client = useQueryClient();
  const { data: sounds, isLoading } = useSounds(server.id);
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);

  const upload = useMutation({
    mutationFn: (file: File) => uploadSound(server.id, file, nameFromFile(file)),
    onSuccess: () => {
      setError(null);
      void client.invalidateQueries({ queryKey: soundKeys.server(server.id) });
    },
    onError: (err: Error) => setError(err.message),
  });

  const onFile = async (file: File | undefined) => {
    if (!file) return;
    if (file.size > MAX_SOUND_BYTES) {
      setError("Sound must be 1 MB or smaller.");
      return;
    }
    const seconds = await probeDuration(file);
    if (seconds !== null && seconds > MAX_SOUND_SECS + 0.15) {
      setError(`Sounds must be ${MAX_SOUND_SECS} seconds or shorter (that one is ${seconds.toFixed(1)}s).`);
      return;
    }
    upload.mutate(file);
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">Soundboard</h3>
          <p className="text-xs text-ink-muted">
            MP3, OGG, WAV or FLAC up to 1 MB and {MAX_SOUND_SECS} seconds.
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
        accept="audio/mpeg,audio/ogg,audio/wav,audio/x-wav,audio/flac,audio/mp4,audio/aac"
        className="hidden"
        onChange={(e) => {
          void onFile(e.target.files?.[0]);
          e.target.value = "";
        }}
      />
      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}

      {isLoading ? (
        <p className="text-sm text-ink-muted">Loading…</p>
      ) : sounds && sounds.length > 0 ? (
        <ul className="max-h-80 space-y-2 overflow-y-auto">
          {sounds.map((sound) => (
            <SoundRow key={sound.id} server={server} sound={sound} />
          ))}
        </ul>
      ) : (
        <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-ink-muted">
          No sounds yet. Upload one to play it in this server's voice channels.
        </p>
      )}
    </div>
  );
}
