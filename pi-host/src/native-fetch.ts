import { bridge } from "./bridge.ts";

type PendingFetch = {
  controller?: ReadableStreamDefaultController<Uint8Array>;
  resolve: (r: Response) => void;
  reject: (e: unknown) => void;
};

const pending = new Map<string, PendingFetch>();
let seq = 0;

const toBase64 = (buf: ArrayBuffer): string => {
  let out = "";
  const bytes = new Uint8Array(buf);
  for (let i = 0; i < bytes.length; i += 0x8000) {
    out += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(out);
};

const fromBase64 = (b64: string): Uint8Array =>
  Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));

const browserFetch = globalThis.fetch.bind(globalThis);

const collectHeaders = (req: Request, initHeaders?: HeadersInit): Record<string, string> => {
  const out: Record<string, string> = Object.fromEntries(req.headers.entries());
  if (!initHeaders) return out;
  if (initHeaders instanceof Headers) {
    initHeaders.forEach((v, k) => {
      out[k] = v;
    });
  } else if (Array.isArray(initHeaders)) {
    for (const [k, v] of initHeaders) out[k] = v;
  } else {
    for (const [k, v] of Object.entries(initHeaders)) {
      if (v !== undefined) out[k] = String(v);
    }
  }
  return out;
};

export async function nativeFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const req = new Request(input, init);
  if (new URL(req.url).origin === location.origin) {
    return browserFetch(input, init);
  }
  const id = `f${++seq}`;
  const body = req.method === "GET" || req.method === "HEAD" ? null : await req.arrayBuffer();
  const signal = init?.signal ?? req.signal;
  signal?.throwIfAborted();
  const headers = collectHeaders(req, init?.headers);

  return new Promise<Response>((resolve, reject) => {
    pending.set(id, { resolve, reject });
    signal?.addEventListener(
      "abort",
      () => {
        const entry = pending.get(id);
        if (!entry) return;
        pending.delete(id);
        bridge({ type: "fetch_abort", id });
        const err = signal.reason ?? new DOMException("Aborted", "AbortError");
        if (entry.controller) entry.controller.error(err);
        else entry.reject(err);
      },
      { once: true },
    );
    bridge({
      type: "fetch",
      id,
      method: req.method,
      url: req.url,
      headers,
      body: body && body.byteLength > 0 ? toBase64(body) : null,
    });
  });
}

export const fetchEvents = {
  head(id: string, status: number, statusText: string, headers: Record<string, string>) {
    const entry = pending.get(id);
    if (!entry) return;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        entry.controller = controller;
      },
    });
    const nullBody = status === 204 || status === 304;
    entry.resolve(new Response(nullBody ? null : stream, { status, statusText, headers }));
  },
  chunk(id: string, b64: string) {
    pending.get(id)?.controller?.enqueue(fromBase64(b64));
  },
  end(id: string) {
    const entry = pending.get(id);
    pending.delete(id);
    try {
      entry?.controller?.close();
    } catch {
      // already closed
    }
  },
  error(id: string, message: string) {
    const entry = pending.get(id);
    pending.delete(id);
    const err = new TypeError(`Network error: ${message}`);
    if (entry?.controller) entry.controller.error(err);
    else entry?.reject(err);
  },
};
