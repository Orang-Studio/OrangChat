import { api } from '../../lib/api';


export const getMediaProxyUrl = (url: string, signal?: AbortSignal) =>
  api<{ url: string }>(`/media/sign?url=${encodeURIComponent(url)}`, { signal }).then(
    (r) => r.url,
  );
