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

/** "14:05" for today, "Jul 12, 14:05" otherwise. */
export function formatMessageTime(iso: string): string {
  const date = new Date(iso);
  return isSameDay(date, new Date()) ? timeFmt.format(date) : dateTimeFmt.format(date);
}

/** Full date + time, for tooltips / titles. */
export function formatFullTime(iso: string): string {
  return fullFmt.format(new Date(iso));
}

/** True when two timestamps are close enough to visually group messages. */
export function withinGroupWindow(aIso: string, bIso: string, ms = 5 * 60_000): boolean {
  return Math.abs(new Date(aIso).getTime() - new Date(bIso).getTime()) <= ms;
}
