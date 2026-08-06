import { api } from '../../lib/api';

export interface LinkPreviewData {
  url: string;
  siteName: string;
  title: string | null;
  description: string | null;
  imageUrl: string | null;
  /**
   * Set when the server resolved the link to a playable video (an Instagram
   * post). Already a same-origin proxy URL, and `imageUrl` is then its poster.
   */
  videoUrl?: string | null;
}

export const getLinkPreview = (url: string, signal?: AbortSignal) =>
  api<LinkPreviewData>(`/link-preview?url=${encodeURIComponent(url)}`, { signal });
