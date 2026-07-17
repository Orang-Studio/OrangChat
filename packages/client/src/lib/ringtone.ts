/**
 * Call audio - ring patterns plus the short join/leave/decline cues - all
 * synthesised with WebAudio so no audio asset ships.
 *
 * Deliberately not gated on the notification permission or the `oc-notifications`
 * preference: those cover background message alerts, whereas a call is happening
 * right now and must be audible to be answerable. The visible popup is the
 * fallback - browsers block audio until the page has been interacted with, and
 * every failure path here is swallowed rather than surfaced.
 */

type Pattern = {
  /** Frequencies layered for the tone, in Hz. */
  tones: number[];
  /** Seconds the tone sounds for. */
  on: number;
  /** Seconds of silence before it repeats. */
  off: number;
  gain: number;
};

/** Classic US ring: a 440+480 Hz pair, two seconds on, four off. */
const INCOMING: Pattern = { tones: [440, 480], on: 2, off: 4, gain: 0.12 };
/** Ringback for the caller: quieter, so it sits under conversation. */
const OUTGOING: Pattern = { tones: [440, 480], on: 1, off: 3, gain: 0.05 };

let ctx: AudioContext | null = null;
let timer: ReturnType<typeof setInterval> | null = null;
let active: "incoming" | "outgoing" | null = null;
/** Oscillators already scheduled, so answering can cut the tone mid-burst. */
let voices: OscillatorNode[] = [];

function context(): AudioContext | null {
  if (ctx) return ctx;
  const Ctor = window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  try {
    ctx = new Ctor();
  } catch {
    return null;
  }
  return ctx;
}

/** One burst of the pattern, with short fades so it does not click. */
function burst(pattern: Pattern): void {
  const audio = context();
  if (!audio) return;
  const start = audio.currentTime;
  const end = start + pattern.on;
  for (const frequency of pattern.tones) {
    const osc = audio.createOscillator();
    const gain = audio.createGain();
    osc.frequency.value = frequency;
    osc.type = "sine";
    gain.gain.setValueAtTime(0, start);
    gain.gain.linearRampToValueAtTime(pattern.gain, start + 0.05);
    gain.gain.setValueAtTime(pattern.gain, end - 0.05);
    gain.gain.linearRampToValueAtTime(0, end);
    osc.connect(gain).connect(audio.destination);
    osc.start(start);
    osc.stop(end + 0.02);
    voices.push(osc);
    osc.onended = () => {
      voices = voices.filter((v) => v !== osc);
    };
  }
}

function play(kind: "incoming" | "outgoing", pattern: Pattern): void {
  if (active === kind) return;
  stopRinging();
  active = kind;

  const audio = context();
  if (!audio) return;
  // Autoplay policy parks the context until a gesture; resuming is best-effort
  // and the popup still shows if it never unblocks.
  void audio.resume().catch(() => {});

  burst(pattern);
  timer = setInterval(() => burst(pattern), (pattern.on + pattern.off) * 1000);
}

/** Ring for an inbound call until answered, declined, or cancelled. */
export function startIncomingRing(): void {
  play("incoming", INCOMING);
}

/** Ringback while our own outbound call waits to be picked up. */
export function startOutgoingRing(): void {
  play("outgoing", OUTGOING);
}

/** One note in a cue, scheduled `at` seconds after the cue starts. */
type Note = { freq: number; at: number; dur: number };

/**
 * A short, non-repeating sequence. Unlike the ring patterns these are fire-and-
 * forget: they are never cancelled, so they stay out of `voices` and a call that
 * ends mid-cue still lets the cue finish.
 */
function cue(notes: Note[], gain: number): void {
  const audio = context();
  if (!audio) return;
  void audio.resume().catch(() => {});
  const base = audio.currentTime;
  for (const note of notes) {
    const start = base + note.at;
    const end = start + note.dur;
    const osc = audio.createOscillator();
    const amp = audio.createGain();
    osc.frequency.value = note.freq;
    osc.type = "sine";
    amp.gain.setValueAtTime(0, start);
    amp.gain.linearRampToValueAtTime(gain, start + 0.015);
    amp.gain.setValueAtTime(gain, end - 0.03);
    amp.gain.linearRampToValueAtTime(0, end);
    osc.connect(amp).connect(audio.destination);
    osc.start(start);
    osc.stop(end + 0.02);
  }
}

/** Someone joined the call: a rising two-note chirp. */
export function playJoinSound(): void {
  cue(
    [
      { freq: 587.33, at: 0, dur: 0.09 },
      { freq: 880, at: 0.09, dur: 0.13 },
    ],
    0.1,
  );
}

/** Someone left the call: the join chirp inverted. */
export function playLeaveSound(): void {
  cue(
    [
      { freq: 880, at: 0, dur: 0.09 },
      { freq: 587.33, at: 0.09, dur: 0.13 },
    ],
    0.1,
  );
}

/** Our callee refused: a flat low double-blip, distinct from a plain hang-up. */
export function playDeclineSound(): void {
  cue(
    [
      { freq: 392, at: 0, dur: 0.12 },
      { freq: 392, at: 0.18, dur: 0.22 },
    ],
    0.09,
  );
}

export function stopRinging(): void {
  if (timer !== null) {
    clearInterval(timer);
    timer = null;
  }
  // Silence the burst already in flight rather than letting it ring on for its
  // remaining couple of seconds after the call is answered.
  for (const osc of voices) {
    try {
      osc.stop();
    } catch {
      // Already stopped; nothing to do.
    }
  }
  voices = [];
  active = null;
}
