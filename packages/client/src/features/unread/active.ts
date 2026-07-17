/**
 * The channel the user is currently looking at. Tracked outside React so the
 * socket layer (which runs outside the component tree) can decide whether an
 * incoming message should mark a channel unread or is already being read.
 */
let activeChannelId: string | null = null;

export function setActiveChannel(id: string | null): void {
  activeChannelId = id;
}

export function getActiveChannel(): string | null {
  return activeChannelId;
}
