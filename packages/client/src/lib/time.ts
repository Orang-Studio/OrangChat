const timeFmt = new Intl.DateTimeFormat(undefined, {
  hour: "2-digit",
  minute: "2-digit",
});
const dateTimeFmt = new Intl.DateTimeFormat(undefined, {
  month: "short",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});
const fullFmt = new Intl.DateTimeFormat(undefined, {
  dateStyle: "long",
  timeStyle: "short",
});

function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}


export function formatMessageTime(iso: string): string {
  const date = new Date(iso);
  return isSameDay(date, new Date()) ? timeFmt.format(date) : dateTimeFmt.format(date);
}


export function formatFullTime(iso: string): string {
  return fullFmt.format(new Date(iso));
}


const dayFmt = new Intl.DateTimeFormat(undefined, {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
});

/** Local calendar day, stable to compare two timestamps by. */
export function dayKey(iso: string): string {
  const d = new Date(iso);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

/** 0 for today, 1 for yesterday, otherwise the whole-day distance. */
export function daysAgo(iso: string, now = new Date()): number {
  const d = new Date(iso);
  const a = Date.UTC(d.getFullYear(), d.getMonth(), d.getDate());
  const b = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((b - a) / 86_400_000);
}

export function formatDayLabel(iso: string): string {
  return dayFmt.format(new Date(iso));
}


export function withinGroupWindow(aIso: string, bIso: string, ms = 5 * 60_000): boolean {
  return Math.abs(new Date(aIso).getTime() - new Date(bIso).getTime()) <= ms;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const MONTH = 30 * DAY;
const YEAR = 365 * DAY;

/** Compact single-unit relative time for list rows: "12h", "4d", "9mo", "2y". */
export function formatShortRelativeTime(iso: string, now = Date.now()): string {
  const diff = Math.max(0, now - new Date(iso).getTime());
  if (diff < MINUTE) return "now";
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)}m`;
  if (diff < DAY) return `${Math.floor(diff / HOUR)}h`;
  if (diff < MONTH) return `${Math.floor(diff / DAY)}d`;
  if (diff < YEAR) return `${Math.floor(diff / MONTH)}mo`;
  return `${Math.floor(diff / YEAR)}y`;
}
