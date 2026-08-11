

type Pattern = {

  tones: number[];

  on: number;

  off: number;
  gain: number;
};


const INCOMING: Pattern = { tones: [440, 480], on: 2, off: 4, gain: 0.12 };

const OUTGOING: Pattern = { tones: [440, 480], on: 1, off: 3, gain: 0.05 };

let ctx: AudioContext | null = null;
let timer: ReturnType<typeof setInterval> | null = null;
let active: "incoming" | "outgoing" | null = null;

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
  void audio.resume().catch(() => {});

  burst(pattern);
  timer = setInterval(() => burst(pattern), (pattern.on + pattern.off) * 1000);
}


export function startIncomingRing(): void {
  play("incoming", INCOMING);
}


export function startOutgoingRing(): void {
  play("outgoing", OUTGOING);
}


type Note = { freq: number; at: number; dur: number };


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


export function playJoinSound(): void {
  cue(
    [
      { freq: 587.33, at: 0, dur: 0.09 },
      { freq: 880, at: 0.09, dur: 0.13 },
    ],
    0.1,
  );
}


export function playLeaveSound(): void {
  cue(
    [
      { freq: 880, at: 0, dur: 0.09 },
      { freq: 587.33, at: 0.09, dur: 0.13 },
    ],
    0.1,
  );
}


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
  for (const osc of voices) {
    try {
      osc.stop();
    } catch {
    }
  }
  voices = [];
  active = null;
}
