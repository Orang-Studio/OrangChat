import { useQuery } from '@tanstack/react-query';
import { ExternalLink } from 'lucide-react';
import type { Attachment } from '@orangchat/shared';
import { getLinkPreview, type LinkPreviewData } from './link-preview-api';
import { getMediaProxyUrl } from './media-proxy-api';
import { InviteCard } from '../servers/InviteCard';
import { parseInviteUrl } from '../servers/invite-url';
import { AudioAttachment, ImagePreview, VideoAttachment } from './MessageAttachments';

const MAX_EMBEDS = 5;
const IMAGE_PATH = /\.(?:avif|gif|jpe?g|png|webp)$/i;
const VIDEO_PATH = /\.(?:m4v|mp4|ogv|webm)$/i;
const AUDIO_PATH = /\.(?:aac|flac|m4a|mp3|oga|ogg|wav)$/i;

/** Pull safe HTTP(S) URLs from message prose while ignoring inline/fenced code. */
export function extractEmbedUrls(content: string): string[] {
  const prose = content.replace(/```[\s\S]*?```/g, ' ').replace(/`[^`\n]*`/g, ' ');
  const matches = prose.match(/https?:\/\/[^\s<]+/gi) ?? [];
  const urls: string[] = [];

  for (const raw of matches) {
    let candidate = raw.replace(/[.,;:!?"']+$/g, '');
    for (const [open, close] of [
      ['(', ')'],
      ['[', ']'],
      ['{', '}'],
    ] as const) {
      while (
        candidate.endsWith(close) &&
        candidate.split(close).length > candidate.split(open).length
      ) {
        candidate = candidate.slice(0, -1);
      }
    }

    try {
      const url = new URL(candidate);
      if (!/^https?:$/.test(url.protocol)) continue;
      url.hash = '';
      const normalized = url.toString();
      if (!urls.includes(normalized)) urls.push(normalized);
    } catch {
      // RichText leaves malformed URLs as text; previews should do the same.
    }
    if (urls.length === MAX_EMBEDS) break;
  }
  return urls;
}

/** Standalone media links render as the media itself, without repeating the raw URL. */
export function isDirectMediaMessage(content: string): boolean {
  const trimmed = content.trim();
  if (/\s/.test(trimmed)) return false;
  try {
    const url = new URL(trimmed);
    return (
      IMAGE_PATH.test(url.pathname) ||
      VIDEO_PATH.test(url.pathname) ||
      AUDIO_PATH.test(url.pathname)
    );
  } catch {
    return false;
  }
}

function youtubeVideo(url: URL): { id: string; start?: number } | null {
  const host = url.hostname.toLowerCase().replace(/^www\./, '');
  let id: string | null = null;
  if (host === 'youtu.be') id = url.pathname.split('/').filter(Boolean)[0] ?? null;
  if (['youtube.com', 'm.youtube.com', 'music.youtube.com'].includes(host)) {
    if (url.pathname === '/watch') id = url.searchParams.get('v');
    else if (/^\/(?:embed|live|shorts)\//.test(url.pathname)) {
      id = url.pathname.split('/').filter(Boolean)[1] ?? null;
    }
  }
  if (!id || !/^[a-zA-Z0-9_-]{11}$/.test(id)) return null;

  const rawStart = url.searchParams.get('t') ?? url.searchParams.get('start');
  const start = rawStart ? parseStartTime(rawStart) : undefined;
  return { id, start };
}

function parseStartTime(value: string): number | undefined {
  if (/^\d+$/.test(value)) return Number(value);
  const match = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(value);
  if (!match) return undefined;
  const seconds = Number(match[1] ?? 0) * 3600 + Number(match[2] ?? 0) * 60 + Number(match[3] ?? 0);
  return seconds || undefined;
}

function domainLabel(url: URL) {
  return url.hostname.replace(/^www\./, '');
}

function YoutubeEmbed({ url, video }: { url: URL; video: { id: string; start?: number } }) {
  const query = video.start ? `?start=${video.start}` : '';
  return (
    <div className="mt-2 w-full max-w-xl overflow-hidden rounded-xl border border-border bg-black shadow-sm">
      <iframe
        src={`https://www.youtube-nocookie.com/embed/${video.id}${query}`}
        title={`YouTube video from ${domainLabel(url)}`}
        loading="lazy"
        allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
        allowFullScreen
        referrerPolicy="strict-origin-when-cross-origin"
        className="aspect-video w-full"
      />
    </div>
  );
}

function DirectMediaEmbed({ url }: { url: URL }) {
  const href = url.toString();
  // The bytes are fetched through our signed same-origin proxy, never straight
  // from the remote host, so rendering the media can't hand the poster a log of
  // every viewer's IP. Until the signed URL arrives there's nothing to load.
  const { data: proxied, isError } = useQuery({
    queryKey: ['media-proxy', href],
    queryFn: ({ signal }) => getMediaProxyUrl(href, signal),
    staleTime: 60 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: false,
  });
  // Not proxyable (private host, too large, not really media): fall back to a
  // plain link card, which only ever touches our origin.
  if (isError) return <PageEmbed url={url} />;
  if (!proxied) return null;

  const filename = (() => {
    const raw = url.pathname.split('/').filter(Boolean).at(-1) ?? 'media';
    try { return decodeURIComponent(raw); } catch { return raw; }
  })();
  const attachment = (contentType: string): Attachment => ({
    id: `link-${href}`,
    url: proxied,
    filename,
    contentType,
    size: 0,
  });

  if (IMAGE_PATH.test(url.pathname)) {
    return (
      <div className="mt-2">
        <ImagePreview attachment={attachment(`image/${url.pathname.split('.').at(-1)}`)} />
      </div>
    );
  }
  if (VIDEO_PATH.test(url.pathname)) {
    return (
      <div className="mt-2">
        <VideoAttachment attachment={attachment(`video/${url.pathname.split('.').at(-1)}`)} />
      </div>
    );
  }
  if (AUDIO_PATH.test(url.pathname)) {
    return (
      <div className="mt-2">
        <AudioAttachment attachment={attachment(`audio/${url.pathname.split('.').at(-1)}`)} />
      </div>
    );
  }
  return null;
}

/**
 * A link the server resolved to a video of its own - an Instagram post, today.
 * Rendered as the video, because that is what the link is; the card underneath
 * keeps the caption and a way back to the post.
 */
function ResolvedVideoEmbed({ url, preview }: { url: URL; preview: LinkPreviewData }) {
  const href = url.toString();
  const attachment: Attachment = {
    id: `link-${href}`,
    url: preview.videoUrl!,
    filename: preview.title || domainLabel(url),
    contentType: 'video/mp4',
    size: 0,
  };

  return (
    <div className="mt-2 max-w-sm">
      <VideoAttachment attachment={attachment} />
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer nofollow"
        className="mt-1 flex items-center gap-1.5 text-xs font-medium text-ink-muted hover:text-ink-secondary"
      >
        {preview.siteName}
        {preview.title && <span className="text-ink-secondary">{preview.title}</span>}
        <ExternalLink aria-hidden className="size-3" />
      </a>
      {preview.description && (
        <p className="mt-1 line-clamp-3 text-xs leading-relaxed text-ink-secondary">
          {preview.description}
        </p>
      )}
    </div>
  );
}

function PageEmbed({ url }: { url: URL }) {
  const href = url.toString();
  const { data } = useQuery({
    queryKey: ['link-preview', href],
    queryFn: ({ signal }) => getLinkPreview(href, signal),
    staleTime: 60 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: false,
  });
  const title = data?.title || domainLabel(url);

  if (data?.videoUrl) return <ResolvedVideoEmbed url={url} preview={data} />;

  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer nofollow"
      className="mt-2 flex w-full max-w-xl overflow-hidden rounded-lg border border-border bg-surface-1 shadow-sm transition-colors hover:border-border-strong hover:bg-surface-3"
    >
      <span className="min-w-0 flex-1 p-3">
        <span className="mb-1 flex items-center gap-1.5 text-xs font-medium text-ink-muted">
          {data?.siteName || domainLabel(url)}
          <ExternalLink aria-hidden className="size-3" />
        </span>
        <span className="block truncate text-sm font-semibold text-primary">{title}</span>
        {data?.description && (
          <span className="mt-1 line-clamp-3 text-xs leading-relaxed text-ink-secondary">
            {data.description}
          </span>
        )}
        {!data?.title && !data?.description && (
          <span className="mt-1 block truncate text-xs text-ink-muted">{href}</span>
        )}
      </span>
      {data?.imageUrl && (
        <img
          src={data.imageUrl}
          alt=""
          loading="lazy"
          referrerPolicy="no-referrer"
          className="hidden w-40 shrink-0 border-l border-border object-cover sm:block"
        />
      )}
    </a>
  );
}

function LinkEmbed({ href }: { href: string }) {
  const url = new URL(href);
  // Our own invite links resolve against our API, so they get a real card with
  // a working Join button rather than a scraped og:title of the login page.
  const invite = parseInviteUrl(url);
  if (invite) return <InviteCard code={invite} className="mt-2" />;
  const youtube = youtubeVideo(url);
  if (youtube) return <YoutubeEmbed url={url} video={youtube} />;
  const media = <DirectMediaEmbed url={url} />;
  if (
    IMAGE_PATH.test(url.pathname) ||
    VIDEO_PATH.test(url.pathname) ||
    AUDIO_PATH.test(url.pathname)
  ) {
    return media;
  }
  return <PageEmbed url={url} />;
}

export function LinkEmbeds({ content }: { content: string }) {
  const urls = extractEmbedUrls(content);
  if (urls.length === 0) return null;
  return (
    <div>
      {urls.map((url) => (
        <LinkEmbed key={url} href={url} />
      ))}
    </div>
  );
}
