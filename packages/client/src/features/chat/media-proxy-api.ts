import { api } from '../../lib/api';

/**
 * Exchange a remote media URL for a signed, same-origin proxy URL. Loading the
 * remote host directly would leak the viewer's IP to whoever posted the link;
 * the proxy makes our server the only party that talks to that host.
 */
export const getMediaProxyUrl = (url: string, signal?: AbortSignal) =>
  api<{ url: string }>(`/media/sign?url=${encodeURIComponent(url)}`, { signal }).then(
    (r) => r.url,
  );
