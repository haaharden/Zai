// Minimal browser/WebView shims for optional Node built-ins referenced by unused acp-kernel helpers
// (disk pack loader & optional tiktoken require).

export function join(...parts: string[]): string {
  return parts
    .filter((p) => typeof p === "string" && p.length > 0)
    .join("/")
    .replace(/\/+/g, "/");
}

export function readFileSync(): never {
  throw new Error("fs.readFileSync is unavailable in WebView");
}

export function readdirSync(): string[] {
  return [];
}

export function createRequire() {
  return (_specifier: string): never => {
    throw new Error("Dynamic require is unavailable in WebView");
  };
}

export default { join, readFileSync, readdirSync, createRequire };
