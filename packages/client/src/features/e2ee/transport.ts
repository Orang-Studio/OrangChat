import { fromBase64, toBase64 } from '@orangchat/shared';
import type { TransferSlot } from './api';
import { putTransferBlob, takeTransferBlob } from './api';
import { socket } from '../../lib/socket';



const CONNECT_TIMEOUT_MS = 10_000;


const LOCAL_CANDIDATES = new Set(['host', 'prflx']);

type Signal = { transferId: string; kind: 'offer' | 'answer' | 'ice' | 'ready'; data: unknown };

export function sendSignal(signal: Signal): void {
  socket.emit('e2ee:transfer:signal', signal);
}

export function onSignal(
  transferId: string,
  handler: (signal: Signal & { from: string }) => void,
): () => void {
  const listener = (payload: Signal & { from: string }) => {
    if (payload.transferId === transferId) handler(payload);
  };
  socket.on('e2ee:transfer:signal', listener);
  return () => socket.off('e2ee:transfer:signal', listener);
}

function newConnection(): RTCPeerConnection {
  return new RTCPeerConnection({ iceServers: [] });
}

async function assertLocal(pc: RTCPeerConnection): Promise<void> {
  const stats = await pc.getStats();
  let pair: RTCIceCandidatePairStats | null = null;
  const candidates = new Map<string, { candidateType?: string; address?: string }>();

  stats.forEach((report) => {
    if (report.type === 'candidate-pair' && (report as RTCIceCandidatePairStats).nominated) {
      pair = report as RTCIceCandidatePairStats;
    }
    if (report.type === 'local-candidate' || report.type === 'remote-candidate') {
      candidates.set(report.id as string, report as { candidateType?: string; address?: string });
    }
  });

  if (!pair) return; // Nothing selected yet; the caller retries or falls back.

  const selected = pair as RTCIceCandidatePairStats;
  for (const id of [selected.localCandidateId, selected.remoteCandidateId]) {
    const candidate = id ? candidates.get(id) : undefined;
    if (!candidate) continue;
    const local =
      (candidate.candidateType && LOCAL_CANDIDATES.has(candidate.candidateType)) ||
      candidate.address?.endsWith('.local') === true;
    if (!local) {
      throw new Error(
        'This connection left the local network. Put both devices on the same Wi-Fi and try again.',
      );
    }
  }
}

export interface Channel {
  send: (bytes: Uint8Array) => void;
  receive: () => Promise<Uint8Array>;
  close: () => void;
}

function wrapChannel(pc: RTCPeerConnection, channel: RTCDataChannel): Channel {
  const inbox: Uint8Array[] = [];
  let waiting: ((bytes: Uint8Array) => void) | null = null;

  channel.binaryType = 'arraybuffer';
  channel.onmessage = (event) => {
    const bytes = new Uint8Array(event.data as ArrayBuffer);
    if (waiting) {
      const resolve = waiting;
      waiting = null;
      resolve(bytes);
    } else {
      inbox.push(bytes);
    }
  };

  return {
    send: (bytes) => channel.send(bytes.slice().buffer),
    receive: () =>
      new Promise((resolve, reject) => {
        const queued = inbox.shift();
        if (queued) return resolve(queued);
        waiting = resolve;
        const timer = setTimeout(
          () => reject(new Error('The other device stopped responding.')),
          CONNECT_TIMEOUT_MS * 3,
        );
        channel.addEventListener('close', () => {
          clearTimeout(timer);
          reject(new Error('The connection to the other device closed.'));
        });
      }),
    close: () => {
      channel.close();
      pc.close();
    },
  };
}

async function connect(transferId: string, role: 'offer' | 'answer'): Promise<Channel> {
  const pc = newConnection();
  const stop = onSignal(transferId, (signal) => {
    void (async () => {
      if (signal.kind === 'ice' && signal.data) {
        await pc.addIceCandidate(signal.data as RTCIceCandidateInit).catch(() => {});
      }
      if (role === 'offer' && signal.kind === 'answer') {
        await pc.setRemoteDescription(signal.data as RTCSessionDescriptionInit);
      }
      if (role === 'answer' && signal.kind === 'offer') {
        await pc.setRemoteDescription(signal.data as RTCSessionDescriptionInit);
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        sendSignal({ transferId, kind: 'answer', data: answer });
      }
    })();
  });

  pc.onicecandidate = (event) => {
    if (event.candidate) {
      sendSignal({ transferId, kind: 'ice', data: event.candidate.toJSON() });
    }
  };

  const opened = new Promise<RTCDataChannel>((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error('Could not reach the other device directly.')),
      CONNECT_TIMEOUT_MS,
    );
    const settle = (channel: RTCDataChannel) => {
      clearTimeout(timer);
      resolve(channel);
    };

    if (role === 'offer') {
      const channel = pc.createDataChannel('orangchat-transfer', { ordered: true });
      channel.onopen = () => settle(channel);
      void (async () => {
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        sendSignal({ transferId, kind: 'offer', data: offer });
      })();
    } else {
      pc.ondatachannel = (event) => {
        event.channel.onopen = () => settle(event.channel);
        if (event.channel.readyState === 'open') settle(event.channel);
      };
    }
  });

  try {
    const channel = await opened;
    await assertLocal(pc);
    return wrapChannel(pc, channel);
  } catch (error) {
    pc.close();
    throw error;
  } finally {
    stop();
  }
}

export const openOfferer = (transferId: string) => connect(transferId, 'offer');
export const openAnswerer = (transferId: string) => connect(transferId, 'answer');


export async function relayPut(
  transferId: string,
  slot: TransferSlot,
  bytes: Uint8Array,
): Promise<void> {
  await putTransferBlob(transferId, slot, toBase64(bytes));
}

export async function relayTake(
  transferId: string,
  slot: TransferSlot,
  { attempts = 30, everyMs = 2000 }: { attempts?: number; everyMs?: number } = {},
): Promise<Uint8Array> {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const { blob } = await takeTransferBlob(transferId, slot);
      return fromBase64(blob);
    } catch {
      await new Promise((resolve) => setTimeout(resolve, everyMs));
    }
  }
  throw new Error('The transfer did not arrive. Start it again on both devices.');
}
