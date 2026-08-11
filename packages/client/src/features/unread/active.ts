
let activeChannelId: string | null = null;

export function setActiveChannel(id: string | null): void {
  activeChannelId = id;
}

export function getActiveChannel(): string | null {
  return activeChannelId;
}
