declare const ZaiBridge: { postMessage(message: string): void };

export const bridge = (msg: Record<string, unknown>) => {
  ZaiBridge.postMessage(JSON.stringify(msg));
};

let seq = 0;
const pending = new Map<string, { resolve: (v: any) => void; reject: (e: any) => void }>();

export function bridgeCall(type: string, args: Record<string, unknown>): Promise<any> {
  const id = `k${++seq}`;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    bridge({ ...args, type, id });
  });
}

export function settle(id: string, ok: boolean, value: any) {
  const entry = pending.get(id);
  if (!entry) return;
  pending.delete(id);
  if (ok) {
    entry.resolve(value);
  } else {
    entry.reject(Object.assign(new Error(value?.message ?? String(value)), value));
  }
}
