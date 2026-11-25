import "./polyfills.ts";
import {
  createModels,
  InMemoryCredentialStore,
  type Credential,
  type CredentialStore,
} from "@earendil-works/pi-ai";
import { registerBunOAuthFlows } from "@earendil-works/pi-ai/bun-oauth";
import { AuthController } from "./auth.ts";
import { bridge, settle } from "./bridge.ts";
import { fetchEvents, nativeFetch } from "./native-fetch.ts";
import { ProviderRegistry, PRIMARY_OAUTH_PROVIDER_ID } from "./providers.ts";
import { QuotaTracker } from "./quota.ts";
import { SessionManager } from "./sessions.ts";
import { dispatch } from "./shims/http.ts";

declare const __PI_VERSION__: string;

globalThis.fetch = nativeFetch as typeof fetch;
registerBunOAuthFlows();

class MirroredCredentialStore implements CredentialStore {
  private readonly inner = new InMemoryCredentialStore();

  async load(saved: Record<string, Credential>) {
    for (const [id, cred] of Object.entries(saved)) {
      await this.inner.modify(id, async () => cred);
    }
  }

  private async mirror() {
    const all: Record<string, Credential> = {};
    for (const info of await this.inner.list()) {
      const cred = await this.inner.read(info.providerId);
      if (cred) all[info.providerId] = cred;
    }
    bridge({ type: "credentials_changed", json: JSON.stringify(all) });
  }

  read: CredentialStore["read"] = (id, opts) => this.inner.read(id, opts);
  list: CredentialStore["list"] = (opts) => this.inner.list(opts);

  modify: CredentialStore["modify"] = async (id, fn, opts) => {
    const res = await this.inner.modify(id, fn, opts);
    await this.mirror();
    return res;
  };

  delete: CredentialStore["delete"] = async (id, opts) => {
    await this.inner.delete(id, opts);
    await this.mirror();
  };
}

const credentials = new MirroredCredentialStore();
const models = createModels({ credentials });
const providerRegistry = new ProviderRegistry(models, credentials);
providerRegistry.registerBuiltins();
const quotaTracker = new QuotaTracker(models, credentials);

const emitStatus = async () => {
  const providers = await providerRegistry.buildVisibleStatus();
  bridge({ type: "status", providers });
};

const authController = new AuthController(models, async () => {
  await emitStatus();
  void quotaTracker.refresh(PRIMARY_OAUTH_PROVIDER_ID, true);
});
const sessionManager = new SessionManager(models, quotaTracker);

const guard = (op: string, fn: () => Promise<void>, session?: string) => {
  fn().catch((e) =>
    bridge({ type: "error", op, session, message: String(e?.message ?? e) }),
  );
};

const arg = (json: string) => JSON.parse(json);

(globalThis as any).ZaiHost = {
  init: (json: string) =>
    guard("init", async () => {
      const a = arg(json);
      authController.setDeviceId(a.deviceId ?? "");
      if (a.credentials) {
        await credentials.load(JSON.parse(a.credentials));
      }
      await providerRegistry.syncCustomProviders(a.customProviders ?? [], a.customKeys ?? {});
      await emitStatus();
      void quotaTracker.refresh(PRIMARY_OAUTH_PROVIDER_ID, false);
    }),
  status: () => guard("status", emitStatus),
  refreshQuota: (json: string) =>
    guard("refreshQuota", async () => {
      const a = arg(json);
      await quotaTracker.refresh(a.provider ?? PRIMARY_OAUTH_PROVIDER_ID, !!a.force);
    }),
  compact: (json: string) => {
    const a = arg(json);
    guard("compact", () => sessionManager.compactNow(a.session), a.session);
  },
  syncCustomProviders: (json: string) =>
    guard("syncCustomProviders", async () => {
      const a = arg(json);
      await providerRegistry.syncCustomProviders(a.providers ?? [], a.keys ?? {});
      await emitStatus();
    }),
  lookupModels: (json: string) => JSON.stringify(providerRegistry.lookupModels(arg(json).ids ?? [])),
  login: (json: string) =>
    guard("login", () => {
      const a = arg(json);
      return authController.startLogin(a.provider, a.type, a.method);
    }),
  cancelLogin: () => authController.cancelLogin(),
  answer: (json: string) => {
    const a = arg(json);
    authController.answerPrompt(a.id, a.value, a.cancel);
  },
  logout: (json: string) =>
    guard("logout", async () => {
      const provider = arg(json).provider;
      await authController.logout(provider);
      quotaTracker.clear(provider);
    }),
  configure: (json: string) => {
    const a = arg(json);
    guard("configure", () => sessionManager.configure(a), a.session);
  },
  prompt: (json: string) => {
    const a = arg(json);
    guard("prompt", () => sessionManager.runPrompt(a.session, a.text), a.session);
  },
  abort: (json: string) => sessionManager.abort(arg(json).session),
  dispose: (json: string) => sessionManager.dispose(arg(json).session),
  reply: (json: string) => {
    const a = arg(json);
    settle(a.id, a.ok, a.value);
  },
  fetchHead: (json: string) => {
    const a = arg(json);
    fetchEvents.head(a.id, a.status, a.statusText, a.headers);
  },
  fetchChunk: (id: string, b64: string) => fetchEvents.chunk(id, b64),
  fetchEnd: (id: string) => fetchEvents.end(id),
  fetchError: (id: string, message: string) => fetchEvents.error(id, message),
  http: (json: string) => {
    const a = arg(json);
    dispatch(a.port, a.id, a.url, a.method);
  },
};

bridge({ type: "ready", pi: __PI_VERSION__ });
