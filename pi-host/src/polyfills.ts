// Make atob tolerant of base64url JWT payloads (used by openai-codex decodeJwt / extractAccountId).
const nativeAtob = globalThis.atob?.bind(globalThis);
if (nativeAtob) {
  globalThis.atob = (input: string) => {
    const cleaned = String(input).replace(/\s+/g, "").replace(/-/g, "+").replace(/_/g, "/");
    const pad = cleaned.length % 4;
    const padded = pad === 0 ? cleaned : cleaned + "=".repeat(4 - pad);
    return nativeAtob(padded);
  };
}

const Signal = AbortSignal as any;

AbortSignal.prototype.throwIfAborted ??= function (this: AbortSignal) {
  if (this.aborted) throw this.reason;
};

Signal.timeout ??= (ms: number) => {
  const controller = new AbortController();
  setTimeout(() => controller.abort(new DOMException("The operation timed out.", "TimeoutError")), ms);
  return controller.signal;
};

Signal.any ??= (signals: Iterable<AbortSignal>) => {
  const controller = new AbortController();
  for (const s of signals) {
    if (s.aborted) {
      controller.abort(s.reason);
      break;
    }
    s.addEventListener("abort", () => controller.abort(s.reason), { once: true, signal: controller.signal });
  }
  return controller.signal;
};

(URL as any).canParse ??= (url: string, base?: string) => {
  try {
    new URL(url, base);
    return true;
  } catch {
    return false;
  }
};

// Minimal Buffer.from shim for WebView (used by acp-kernel image-dimension scanning).
if (typeof (globalThis as any).Buffer === "undefined") {
  (globalThis as any).Buffer = {
    from(input: string | Uint8Array | ArrayBuffer, encoding?: string): Uint8Array {
      if (input instanceof Uint8Array) return input;
      if (input instanceof ArrayBuffer) return new Uint8Array(input);
      const str = String(input);
      if (encoding === "base64" || encoding === "base64url") {
        const bin = globalThis.atob(str);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return bytes;
      }
      return new TextEncoder().encode(str);
    },
    isBuffer(v: unknown): boolean {
      return v instanceof Uint8Array;
    },
  };
}
