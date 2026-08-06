import { SYSTEM_NOTICES, type SystemNoticeKind } from '@orangchat/shared';
import { sendMessage } from './socket-actions';

/**
 * Says on the conversation that something about it changed.
 *
 * Fire-and-forget on purpose: the notice is a courtesy to the other side, and a
 * failed send must not fail - or even delay - the change it describes. Losing
 * one costs an unexplained background; blocking on one would cost the setting.
 */
export function announce(channelId: string, kind: SystemNoticeKind): void {
  void sendMessage({ channelId, content: SYSTEM_NOTICES[kind] }).catch(() => {});
}
