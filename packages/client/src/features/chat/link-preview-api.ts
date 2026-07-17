import { api } from '../../lib/api';

export interface LinkPreviewData {
  url: string;
  siteName: string;
  title: string | null;
  description: string | null;
  imageUrl: string | null;
}

export const getLinkPreview = (url: string, signal?: AbortSignal) =>
  api<LinkPreviewData>(`/link-preview?url=${encodeURIComponent(url)}`, { signal });
