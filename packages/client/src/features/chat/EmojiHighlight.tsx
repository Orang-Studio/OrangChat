import type { ReactNode } from "react";
import { emojiSpans } from "@orangchat/shared";

/**
 * The draft, with every emoji token and known shortcode picked out. Rendered
 * behind a transparent textarea, so the metrics have to match it exactly -
 * only colour may differ, never weight, size or spacing.
 */
export function highlightEmoji(
  content: string,
  known: (name: string) => boolean,
): ReactNode[] {
  const nodes: ReactNode[] = [];
  let last = 0;
  for (const span of emojiSpans(content, known)) {
    if (span.start > last) nodes.push(content.slice(last, span.start));
    nodes.push(
      <span key={span.start} className="text-primary">
        {content.slice(span.start, span.end)}
      </span>,
    );
    last = span.end;
  }
  // The trailing newline keeps the mirror as tall as the textarea it tracks.
  nodes.push(`${content.slice(last)}\n`);
  return nodes;
}
