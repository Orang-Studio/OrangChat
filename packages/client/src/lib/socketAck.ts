import type { Ack } from "@orangchat/shared";


export function withAck<T>(run: (ack: Ack<T>) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    run((response) => {
      if (response.ok) resolve(response.data);
      else reject(new Error(response.error));
    });
  });
}
