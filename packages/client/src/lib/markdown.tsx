import { Fragment, useState, type ReactNode } from "react";
import { EMOJI_SHORTCODE_SOURCE, EMOJI_TOKEN_SOURCE } from "@orangchat/shared";
import { cn } from "./cn";
import { t } from "./i18n";

const EMOJI_TOKEN_RE = new RegExp("^" + EMOJI_TOKEN_SOURCE, "i");
const EMOJI_SHORTCODE_RE = new RegExp("^" + EMOJI_SHORTCODE_SOURCE, "i");




export interface EmojiRef {
  name: string;
  url: string;
}

export interface MentionContext {

  names?: Record<string, string>;

  usernames?: Record<string, { id: string; name: string }>;

  selfId?: string;

  everyoneHighlights?: boolean;

  onMentionClick?: (userId: string) => void;

  emojis?: Record<string, EmojiRef>;
}

let keySeq = 0;
const nextKey = () => `md${keySeq++}`;

const escapeRegex = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");


export function mentionsViewer(
  content: string,
  selfId: string | undefined,
  username: string | undefined,
): boolean {
  if (!selfId) return false;
  if (content.includes(`<@${selfId}>`)) return true;
  if (/(^|\s)@(everyone|here)\b/.test(content)) return true;
  if (username && new RegExp(`(^|[^\\w])@${escapeRegex(username)}(?![\\w.])`, "i").test(content)) {
    return true;
  }
  return false;
}

/**
 * Covered until clicked. The content stays mounted underneath rather than being
 * swapped in on reveal, so the block is exactly the size of what it hides and
 * the line doesn't reflow when someone opens it.
 */
function Spoiler({ children }: { children: ReactNode }) {
  const [revealed, setRevealed] = useState(false);

  if (revealed) {
    return <span className="rounded bg-surface-1 px-0.5">{children}</span>;
  }
  return (
    <button
      type="button"
      onClick={() => setRevealed(true)}
      aria-label={t("markdown.spoilerClickToReveal")}
      // text-transparent covers bare text; nested marks (code, links) bring
      // their own colours and backgrounds, so those are hidden outright.
      className="rounded bg-ink-muted px-0.5 text-transparent transition-colors hover:bg-ink-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary [&_*]:invisible"
    >
      {children}
    </button>
  );
}

// ── Inline parsing ──────────────────────────────────────
// Ordered so longer/greedier delimiters win (***, ___, ~~ before * _).
const INLINE_RULES: {
  re: RegExp;
  render: (m: RegExpExecArray, ctx: MentionContext) => ReactNode;
}[] = [
  // inline code - no nesting inside
  {
    re: /^`([^`\n]+)`/,
    render: (m) => (
      <code
        key={nextKey()}
        className="rounded bg-surface-1 px-1 py-0.5 font-mono text-[0.85em]"
      >
        {m[1] ?? ""}
      </code>
    ),
  },
  {
    re: /^\|\|([\s\S]+?)\|\|/,
    render: (m, ctx) => (
      <Spoiler key={nextKey()}>{parseInline(m[1] ?? "", ctx)}</Spoiler>
    ),
  },
  {
    re: /^\*\*\*([\s\S]+?)\*\*\*/,
    render: (m, ctx) => (
      <strong key={nextKey()}>
        <em>{parseInline(m[1] ?? "", ctx)}</em>
      </strong>
    ),
  },
  {
    re: /^\*\*([\s\S]+?)\*\*/,
    render: (m, ctx) => (
      <strong key={nextKey()}>{parseInline(m[1] ?? "", ctx)}</strong>
    ),
  },
  {
    re: /^__([\s\S]+?)__/,
    render: (m, ctx) => (
      <span key={nextKey()} className="underline">
        {parseInline(m[1] ?? "", ctx)}
      </span>
    ),
  },
  {
    re: /^~~([\s\S]+?)~~/,
    render: (m, ctx) => (
      <span key={nextKey()} className="line-through">
        {parseInline(m[1] ?? "", ctx)}
      </span>
    ),
  },
  {
    re: /^\*([^*\n]+?)\*/,
    render: (m, ctx) => <em key={nextKey()}>{parseInline(m[1] ?? "", ctx)}</em>,
  },
  {
    re: /^_([^_\n]+?)_/,
    render: (m, ctx) => <em key={nextKey()}>{parseInline(m[1] ?? "", ctx)}</em>,
  },
  {
    re: /^\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/,
    render: (m, ctx) => (
      <a
        key={nextKey()}
        href={m[2] ?? "#"}
        target="_blank"
        rel="noopener noreferrer nofollow"
        className="text-primary hover:underline"
      >
        {parseInline(m[1] ?? "", ctx)}
      </a>
    ),
  },
  {
    re: /^(https?:\/\/[^\s<]+[^\s<.,:;"')\]}])/,
    render: (m) => (
      <a
        key={nextKey()}
        href={m[1] ?? "#"}
        target="_blank"
        rel="noopener noreferrer nofollow"
        className="text-primary hover:underline"
      >
        {m[1] ?? ""}
      </a>
    ),
  },
  {
    re: EMOJI_TOKEN_RE,
    render: (m, ctx) => {
      const name = m[2] ?? "";
      const emoji = ctx.emojis?.[m[3] ?? ""];
      if (!emoji) return <Fragment key={nextKey()}>:{name}:</Fragment>;
      return (
        <img
          key={nextKey()}
          src={emoji.url}
          alt={`:${emoji.name}:`}
          title={`:${emoji.name}:`}
          loading="lazy"
          draggable={false}
          className="oc-emoji inline-block size-8 align-text-bottom"
        />
      );
    },
  },
  // Manually typed :name: custom emoji (legacy/draft-friendly fallback).
  {
    re: EMOJI_SHORTCODE_RE,
    render: (m, ctx) => {
      const name = m[1] ?? "";
      const emoji = Object.values(ctx.emojis ?? {}).find(
        (candidate) => candidate.name.toLowerCase() === name.toLowerCase(),
      );
      if (!emoji) return <Fragment key={nextKey()}>{m[0] ?? `:${name}:`}</Fragment>;
      return (
        <img
          key={nextKey()}
          src={emoji.url}
          alt={`:${emoji.name}:`}
          title={`:${emoji.name}:`}
          loading="lazy"
          draggable={false}
          className="oc-emoji inline-block size-8 align-text-bottom"
        />
      );
    },
  },
  // <@userId> mention
  {
    re: /^<@([a-z0-9]+)>/i,
    render: (m, ctx) => {
      const id = m[1] ?? "";
      const name = ctx.names?.[id] ?? "unknown";
      const isSelf = ctx.selfId === id;
      const className = cn(
        "rounded px-1 font-medium",
        isSelf ? "bg-primary/30 text-primary" : "bg-primary-soft text-primary",
      );
      return ctx.onMentionClick && ctx.names?.[id] ? (
        <button
          key={nextKey()}
          type="button"
          onClick={() => ctx.onMentionClick?.(id)}
          className={cn(className, "cursor-pointer text-inherit hover:bg-primary/30")}
        >
          @{name}
        </button>
      ) : (
        <span key={nextKey()} className={className}>
          @{name}
        </span>
      );
    },
  },
  // @everyone / @here
  {
    re: /^@(everyone|here)\b/,
    render: (m) => (
      <span
        key={nextKey()}
        className="rounded bg-primary/30 px-1 font-medium text-primary"
      >
        @{m[1] ?? ""}
      </span>
    ),
  },
  // @username mention. Dot-separated segments rather than a greedy [a-z0-9_.]
  // run so "@alice." at the end of a sentence keeps its full stop as
  // punctuation. A handle that ends in a dot therefore never resolves, which is
  // legal but vanishingly rare, and losing the highlight beats eating the period
  // of every sentence that ends in a mention.
  {
    re: /^@([a-z0-9_]+(?:\.[a-z0-9_]+)*)/i,
    render: (m, ctx) => {
      const user = ctx.usernames?.[(m[1] ?? "").toLowerCase()];
      // Unresolved handles are ordinary words (and the tail of every email
      // address) - render the literal text so nothing lights up by accident.
      if (!user) return <Fragment key={nextKey()}>{m[0]}</Fragment>;
      const className = cn(
        "rounded px-1 font-medium",
        ctx.selfId === user.id ? "bg-primary/30 text-primary" : "bg-primary-soft text-primary",
      );
      return ctx.onMentionClick ? (
        <button
          key={nextKey()}
          type="button"
          onClick={() => ctx.onMentionClick?.(user.id)}
          className={cn(className, "cursor-pointer text-inherit hover:bg-primary/30")}
        >
          @{user.name}
        </button>
      ) : (
        <span key={nextKey()} className={className}>
          @{user.name}
        </span>
      );
    },
  },
];

function parseInline(text: string, ctx: MentionContext): ReactNode[] {
  const out: ReactNode[] = [];
  let buf = "";
  let i = 0;
  const flush = () => {
    if (buf) {
      out.push(<Fragment key={nextKey()}>{buf}</Fragment>);
      buf = "";
    }
  };

  while (i < text.length) {
    const slice = text.slice(i);
    let matched = false;
    for (const rule of INLINE_RULES) {
      const m = rule.re.exec(slice);
      if (m) {
        flush();
        out.push(rule.render(m, ctx));
        i += m[0].length;
        matched = true;
        break;
      }
    }
    if (!matched) {
      buf += text[i];
      i += 1;
    }
  }
  flush();
  return out;
}

// ── Block parsing ───────────────────────────────────────
type Block =
  | { kind: "code"; lang: string; body: string }
  | { kind: "quote"; body: string }
  | { kind: "text"; body: string };

function parseBlocks(src: string): Block[] {
  const blocks: Block[] = [];
  const fence = /```([\w+-]*)\n?([\s\S]*?)```/g;
  let last = 0;
  let m: RegExpExecArray | null;
  const pushText = (chunk: string) => {
    // group consecutive `> ` lines into quote blocks
    const lines = chunk.split("\n");
    let run: string[] | null = null;
    const flushRun = () => {
      if (run) {
        blocks.push({ kind: "quote", body: run.join("\n") });
        run = null;
      }
    };
    let text: string[] = [];
    const flushText = () => {
      if (text.length) {
        blocks.push({ kind: "text", body: text.join("\n") });
        text = [];
      }
    };
    for (const line of lines) {
      const q = /^>\s?(.*)$/.exec(line);
      if (q) {
        flushText();
        run = run ?? [];
        run.push(q[1] ?? "");
      } else {
        flushRun();
        text.push(line);
      }
    }
    flushRun();
    flushText();
  };

  while ((m = fence.exec(src))) {
    if (m.index > last) pushText(src.slice(last, m.index));
    blocks.push({
      kind: "code",
      lang: m[1] ?? "",
      body: (m[2] ?? "").replace(/\n$/, ""),
    });
    last = fence.lastIndex;
  }
  if (last < src.length) pushText(src.slice(last));
  return blocks;
}

export function RichText({
  content,
  mentions = {},
  mentionUsers = {},
  selfId,
  emojis,
  onMentionClick,
}: {
  content: string;
  mentions?: Record<string, string>;
  mentionUsers?: Record<string, { id: string; name: string }>;
  selfId?: string;
  emojis?: Record<string, EmojiRef>;
  onMentionClick?: (userId: string) => void;
}) {
  const ctx: MentionContext = {
    names: mentions,
    usernames: mentionUsers,
    selfId,
    emojis,
    onMentionClick,
  };
  const blocks = parseBlocks(content);
  return (
    <>
      {blocks.map((b) => {
        if (b.kind === "code") {
          return (
            <pre
              key={nextKey()}
              className="my-1 overflow-x-auto rounded-md border border-border bg-surface-1 p-3 font-mono text-[0.85em]"
            >
              <code>{b.body}</code>
            </pre>
          );
        }
        if (b.kind === "quote") {
          return (
            <blockquote
              key={nextKey()}
              className="my-0.5 border-l-2 border-border-strong pl-3 text-ink-secondary"
            >
              {parseInline(b.body, ctx)}
            </blockquote>
          );
        }
        return (
          <span key={nextKey()} className="whitespace-pre-wrap break-words">
            {parseInline(b.body, ctx)}
          </span>
        );
      })}
    </>
  );
}
