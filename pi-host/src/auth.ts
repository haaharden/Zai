import type { AuthInteraction, AuthPrompt, Models } from "@earendil-works/pi-ai";
import { bridge } from "./bridge.ts";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class AuthController {
  private deviceId = "";
  private loginAbort?: AbortController;
  private promptSeq = 0;
  private readonly prompts = new Map<string, { resolve: (v: string) => void; reject: (e: unknown) => void }>();

  constructor(
    private readonly models: Models,
    private readonly onStatusChanged: () => Promise<void>,
  ) {}

  setDeviceId(id: string) {
    this.deviceId = id;
  }

  private validDeviceId(): string {
    if (!UUID_RE.test(this.deviceId)) {
      this.deviceId = crypto.randomUUID();
    }
    return this.deviceId;
  }

  async startLogin(provider: string, type: "oauth" | "api_key", method?: string) {
    this.loginAbort?.abort();
    const controller = new AbortController();
    this.loginAbort = controller;
    try {
      const c = await this.models.login(
        provider,
        type,
        this.createInteraction(controller.signal, method ?? "browser"),
        {
          getDeviceId: () => this.validDeviceId(),
          agentName: "pi",
        },
      );
      bridge({ type: "login_done", provider, credential: c.type });
    } catch (e: any) {
      bridge({
        type: "login_failed",
        provider,
        message: controller.signal.aborted ? "已取消登录" : String(e?.message ?? e),
      });
    } finally {
      if (this.loginAbort === controller) this.loginAbort = undefined;
      await this.onStatusChanged();
    }
  }

  cancelLogin() {
    this.loginAbort?.abort();
  }

  answerPrompt(id: string, value?: string, cancel?: boolean) {
    const p = this.prompts.get(id);
    if (!p) return;
    this.prompts.delete(id);
    if (cancel) p.reject(new Error("cancelled"));
    else p.resolve(value ?? "");
  }

  async logout(provider: string) {
    await this.models.logout(provider);
    await this.onStatusChanged();
  }

  private createInteraction(signal: AbortSignal, preferredMethod?: string): AuthInteraction {
    return {
      signal,
      notify: (event) => bridge({ type: "auth_event", event }),
      prompt: (p: AuthPrompt) => {
        if (p.type === "select" && preferredMethod) {
          const match = p.options.find((opt) => opt.id === preferredMethod);
          if (match) return Promise.resolve(match.id);
        }
        return new Promise<string>((resolve, reject) => {
          const id = `p${++this.promptSeq}`;
          this.prompts.set(id, { resolve, reject });
          const { signal: promptSignal, ...shown } = p;
          bridge({ type: "auth_prompt", id, prompt: shown });
          const cancel = () => {
            if (!this.prompts.delete(id)) return;
            bridge({ type: "auth_prompt_closed", id });
            reject(new Error("Prompt cancelled"));
          };
          promptSignal?.addEventListener("abort", cancel, { once: true });
          signal.addEventListener("abort", cancel, { once: true });
        });
      },
    };
  }
}
