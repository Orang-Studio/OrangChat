import type { Ack } from "@orangchat/shared";

/** Promisify a socket action's ack callback; rejects on `{ ok: false }`. */
export function withAck<T>(run: (ack: Ack<T>) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    run((response) => {
      if (response.ok) resolve(response.data);
      else reject(new Error(response.error));
    });
  });
}
