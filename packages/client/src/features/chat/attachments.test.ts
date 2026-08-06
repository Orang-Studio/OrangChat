import { describe, expect, it } from 'vitest';
import {
  isSealedThumbnail,
  rememberSealedAttachments,
  sealedAttachmentsOf,
} from '../e2ee/attachments';

describe('sealed media preview metadata', () => {
  it('keeps a sealed video thumbnail linked to its main attachment', () => {
    const ref = {
      fileId: 'video-file',
      attachmentId: 'video-row',
      key: 'video-key',
      nonce: 'video-nonce',
      filename: 'clip.mp4',
      contentType: 'video/mp4',
      size: 1234,
      duration: 41.25,
      thumb: {
        fileId: 'thumb-file',
        attachmentId: 'thumb-row',
        key: 'thumb-key',
        nonce: 'thumb-nonce',
        contentType: 'image/webp',
        size: 456,
      },
    } as const;

    rememberSealedAttachments([ref]);

    expect(sealedAttachmentsOf('video-row')?.duration).toBe(41.25);
    expect(sealedAttachmentsOf('thumb-row')?.attachmentId).toBe('thumb-row');
    expect(isSealedThumbnail('thumb-row')).toBe(true);
  });
});
