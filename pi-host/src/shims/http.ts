import { bridge, bridgeCall } from "../bridge.ts";

type Handler = (req: { url: string; method: string }, res: Response) => void;
type Response = {
  writeHead(status: number, headers?: Record<string, string>): void;
  end(body?: string): void;
};

const servers = new Map<number, Server>();

class Server {
  private listeners = new Map<string, ((e: unknown) => void)[]>();
  port = 0;
  host = "127.0.0.1";

  constructor(private handler: Handler) {}

  on(event: string, fn: (e: unknown) => void) {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), fn]);
    return this;
  }

  once(event: string, fn: (e: unknown) => void) {
    const wrapped = (e: unknown) => {
      this.removeListener(event, wrapped);
      fn(e);
    };
    return this.on(event, wrapped);
  }

  off(event: string, fn: (e: unknown) => void) {
    return this.removeListener(event, fn);
  }

  address() {
    return { port: this.port, address: this.host, family: "IPv4" };
  }

  removeListener(event: string, fn: (e: unknown) => void) {
    this.listeners.set(event, (this.listeners.get(event) ?? []).filter((f) => f !== fn));
    return this;
  }

  emit(event: string, e: unknown) {
    for (const fn of this.listeners.get(event) ?? []) fn(e);
  }

  listen(port: number, host: string, cb?: () => void) {
    this.port = port;
    this.host = host || "127.0.0.1";
    bridgeCall("http_listen", { port: this.port, host: this.host }).then(
      () => {
        servers.set(this.port, this);
        cb?.();
      },
      (err) => {
        this.emit("error", Object.assign(new Error(String(err?.message ?? err)), { code: err?.code }));
      },
    );
    return this;
  }

  close() {
    if (servers.get(this.port) === this) servers.delete(this.port);
    bridge({ type: "http_close", port: this.port });
    return this;
  }

  closeAllConnections() {}

  handle(id: string, url: string, method: string) {
    let status = 200;
    let headers: Record<string, string> = {};
    this.handler(
      { url, method },
      {
        writeHead(s, h) {
          status = s;
          headers = h ?? {};
        },
        end(body = "") {
          bridge({ type: "http_response", id, status, headers, body });
        },
      },
    );
  }
}

export function createServer(handler: Handler) {
  return new Server(handler);
}

export function dispatch(port: number, id: string, url: string, method: string) {
  const server = servers.get(port);
  if (server) {
    server.handle(id, url, method);
  } else {
    bridge({ type: "http_response", id, status: 404, headers: {}, body: "" });
  }
}

export default { createServer };
