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
