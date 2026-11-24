import "./polyfills.ts";
import {
  Agent,
  type AgentEvent,
  type AgentMessage,
  type BeforeToolCallContext,
} from "@earendil-works/pi-agent-core";
import {
  clampThinkingLevel,
  createModels,
  createProvider,
  envApiKeyAuth,
  getSupportedThinkingLevels,
  InMemoryCredentialStore,
  type AuthInteraction,
  type AuthPrompt,
  type Credential,
  type CredentialStore,
  type Model,
} from "@earendil-works/pi-ai";
import { openaiProvider } from "@earendil-works/pi-ai/providers/openai";
import { OPENAI_CODEX_MODELS } from "@earendil-works/pi-ai/providers/openai-codex.models";
import { deepseekProvider } from "@earendil-works/pi-ai/providers/deepseek";
import { anthropicProvider } from "@earendil-works/pi-ai/providers/anthropic";
import { googleProvider } from "@earendil-works/pi-ai/providers/google";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { openAIResponsesApi } from "@earendil-works/pi-ai/api/openai-responses.lazy";
import { anthropicMessagesApi } from "@earendil-works/pi-ai/api/anthropic-messages.lazy";
import { registerBunOAuthFlows } from "@earendil-works/pi-ai/bun-oauth";
import { bridge, bridgeCall, settle } from "./bridge.ts";
import { fetchEvents, nativeFetch } from "./native-fetch.ts";
import {
  createPhoneTools,
  pruneOldScreens,
  savableTranscript,
  type ToolSpec,
} from "./phone-tools.ts";
import { dispatch } from "./shims/http.ts";

declare const __PI_VERSION__: string;

globalThis.fetch = nativeFetch as typeof fetch;
registerBunOAuthFlows();

class MirroredCredentialStore implements CredentialStore {
  private inner = new InMemoryCredentialStore();

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

// Register built-in OpenAI (for ChatGPT subscription OAuth) + catalog reference providers
for (const provider of [
  openaiProvider(),
  deepseekProvider(),
  anthropicProvider(),
  googleProvider(),
]) {
  models.setProvider(provider);
}

const OAUTH_PROVIDERS = new Set(["openai"]);
const SUBSCRIPTION_IDS = new Set(Object.values(OPENAI_CODEX_MODELS).map((m: any) => m.id));
const DEFAULT_CONTEXT = 128_000;
const customIds = new Set<string>();

interface CustomModelConfig {
  id: string;
  contextWindow?: number;
  images?: boolean;
  reasoning?: boolean;
  api?: "openai-completions" | "openai-responses" | "anthropic-messages";
}

interface CustomProviderConfig {
  id: string;
  name: string;
  baseUrl: string;
  models: CustomModelConfig[];
  images: boolean;
}

const normalizeModelId = (id: string) =>
  id.toLowerCase().replace(/^.*\//, "").replace(/:.*$/, "").replace(/^~/, "").replace(/[._]/g, "-");

function catalogModel(id: string): Model<any> | undefined {
  const builtIn = models.getProviders().filter((p) => !customIds.has(p.id));
  const want = normalizeModelId(id);
  for (const p of builtIn) {
    const ms = models.getModels(p.id);
    const hit = ms.find((x) => x.id === id) ?? ms.find((x) => normalizeModelId(x.id) === want);
    if (hit) return hit;
  }
  return undefined;
}

function lookupModels(ids: string[]) {
  const out: Record<string, { contextWindow: number; images: boolean; reasoning: boolean }> = {};
  for (const id of ids) {
    const m = catalogModel(id);
    if (m) {
      out[id] = {
        contextWindow: m.contextWindow,
        images: m.input.includes("image"),
        reasoning: !!m.reasoning,
      };
    }
  }
  return out;
}

function apiBaseUrl(baseUrl: string, api: string): string {
  const clean = baseUrl.trim().replace(/\/+$/, "");
  if (api === "anthropic-messages") {
    return clean.replace(/\/v1$/, "");
  }
  return clean;
}

function buildCustomProvider(cfg: CustomProviderConfig) {
  return createProvider({
    id: cfg.id,
    name: cfg.name,
    baseUrl: cfg.baseUrl,
    auth: { apiKey: envApiKeyAuth(`${cfg.name} API key`, []) },
    models: cfg.models.map((m) => {
      const api = m.api ?? "openai-completions";
      const known = catalogModel(m.id);
      const supportsImages = m.images ?? known?.input.includes("image") ?? cfg.images;
      return {
        id: m.id,
        name: m.id,
        api,
        provider: cfg.id,
        baseUrl: apiBaseUrl(cfg.baseUrl, api),
        reasoning: m.reasoning ?? !!known?.reasoning,
        input: supportsImages ? (["text", "image"] as const) : (["text"] as const),
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
        contextWindow: m.contextWindow || known?.contextWindow || DEFAULT_CONTEXT,
        maxTokens: known?.maxTokens ?? 16_384,
        compat:
          api === "openai-completions"
            ? { supportsStore: false, supportsDeveloperRole: false }
            : undefined,
      };
    }),
    api: {
      "openai-completions": openAICompletionsApi(),
      "openai-responses": openAIResponsesApi(),
      "anthropic-messages": anthropicMessagesApi(),
    },
  });
}

async function syncCustomProviders(list: CustomProviderConfig[], keys: Record<string, string>) {
  const keep = new Set(list.map((c) => c.id));
  for (const id of [...customIds]) {
    if (!keep.has(id)) {
      models.deleteProvider(id);
      await credentials.delete(id);
      customIds.delete(id);
    }
  }
  for (const cfg of list) {
    if (cfg.models.length === 0) continue;
    models.setProvider(buildCustomProvider(cfg));
    customIds.add(cfg.id);
    const key = keys[cfg.id];
    if (key) {
      await credentials.modify(cfg.id, async () => ({ type: "api_key", key }));
    }
  }
}

let deviceId = "";
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function validDeviceId(): string {
  if (!UUID_RE.test(deviceId)) {
    deviceId = crypto.randomUUID();
  }
  return deviceId;
}
let loginAbort: AbortController | undefined;
let promptSeq = 0;
const authPrompts = new Map<string, { resolve: (v: string) => void; reject: (e: unknown) => void }>();

function modelsFor(providerId: string, credentialType?: string) {
  const list = models
    .getModels(providerId)
    .filter((m) => !(providerId === "openai" && credentialType === "oauth") || SUBSCRIPTION_IDS.has(m.id))
    .map((m) => ({
      id: m.id,
      name: m.name,
      thinkingLevels: getSupportedThinkingLevels(m),
      contextWindow: m.contextWindow,
      input: m.input,
    }));
  // Catalog is ordered oldest -> newest; show newest flagship ChatGPT models (e.g. gpt-6.2) first
  return providerId === "openai" && credentialType === "oauth" ? list.reverse() : list;
}

async function emitStatus() {
  const infos = await credentials.list();
  const visibleIds = new Set<string>(["openai", ...customIds]);
  const providers = models
    .getProviders()
    .filter((p) => visibleIds.has(p.id))
    .map((p) => {
      const credType = infos.find((i) => i.providerId === p.id)?.type ?? null;
      return {
        id: p.id,
        name: p.id === "openai" ? "ChatGPT" : p.name,
        credential: credType,
        custom: customIds.has(p.id),
        supportsOAuth: OAUTH_PROVIDERS.has(p.id) && !!p.auth.oauth,
        models: modelsFor(p.id, credType ?? undefined),
      };
    });
  bridge({ type: "status", providers });
}

const createAuthInteraction = (signal: AbortSignal): AuthInteraction => ({
  signal,
  notify: (event) => bridge({ type: "auth_event", event }),
  prompt: (p: AuthPrompt) =>
    new Promise<string>((resolve, reject) => {
      const id = `p${++promptSeq}`;
      authPrompts.set(id, { resolve, reject });
      const { signal: promptSignal, ...shown } = p;
      bridge({ type: "auth_prompt", id, prompt: shown });
      const cancel = () => {
        if (!authPrompts.delete(id)) return;
        bridge({ type: "auth_prompt_closed", id });
        reject(new Error("Prompt cancelled"));
      };
      promptSignal?.addEventListener("abort", cancel, { once: true });
      signal.addEventListener("abort", cancel, { once: true });
    }),
});

async function startLogin(provider: string, type: "oauth" | "api_key") {
  loginAbort?.abort();
  const controller = new AbortController();
  loginAbort = controller;
  try {
    const c = await models.login(provider, type, createAuthInteraction(controller.signal), {
      getDeviceId: validDeviceId,
      agentName: "Zai",
    });
    bridge({ type: "login_done", provider, credential: c.type });
  } catch (e: any) {
    bridge({
      type: "login_failed",
      provider,
      message: controller.signal.aborted ? "已取消登录" : String(e?.message ?? e),
    });
  } finally {
    if (loginAbort === controller) loginAbort = undefined;
    await emitStatus();
  }
}

interface Session {
  id: string;
  agent: Agent;
  running: boolean;
}

const sessions = new Map<string, Session>();

function forwardEvent(sessionId: string, event: AgentEvent) {
  const send = (payload: Record<string, unknown>) => bridge({ ...payload, session: sessionId });
  switch (event.type) {
    case "message_update": {
      const e = event.assistantMessageEvent;
      if (e.type === "text_delta") send({ type: "text_delta", delta: e.delta });
      else if (e.type === "thinking_start") send({ type: "thinking_start" });
      else if (e.type === "thinking_delta") send({ type: "thinking_delta", delta: e.delta });
      else if (e.type === "thinking_end") send({ type: "thinking_end", text: e.content });
      break;
    }
    case "message_end": {
      const m = event.message as any;
      if (m.role === "assistant") {
        const text = (m.content ?? [])
          .filter((c: any) => c.type === "text")
          .map((c: any) => c.text)
          .join("");
        send({
          type: "assistant_end",
          text,
          model: m.model,
          provider: m.provider,
          stopReason: m.stopReason,
          errorMessage: m.errorMessage,
        });
      }
      break;
    }
    case "tool_execution_start":
      send({ type: "tool_start", tool: event.toolName, args: event.args });
      break;
    case "tool_execution_end": {
      const r = (event as any).result;
      const out = (r?.content ?? [])
        .filter((c: any) => c.type === "text")
        .map((c: any) => c.text)
        .join("");
      send({
        type: "tool_end",
        tool: event.toolName,
        isError: !!(event as any).isError,
        output: out.slice(0, 320),
      });
      break;
    }
    case "turn_end":
      send({ type: "turn_end" });
      saveTranscript(sessionId);
      break;
    default:
      break;
  }
}

function saveTranscript(sessionId: string) {
  const s = sessions.get(sessionId);
  if (s) {
    bridge({
      type: "transcript",
      session: sessionId,
      messages: savableTranscript(s.agent.state.messages),
    });
  }
}

async function configureSession(cfg: {
  session: string;
  provider: string;
  modelId: string;
  thinkingLevel?: string;
  systemPrompt: string;
  toolSpecs: ToolSpec[];
  messages?: AgentMessage[];
}) {
  const model = models.getModel(cfg.provider, cfg.modelId);
  if (!model) {
    throw new Error(`未找到模型 ${cfg.provider}/${cfg.modelId}`);
  }
  const prev = sessions.get(cfg.session);
  prev?.agent.abort();

  const thinkingLevel = clampThinkingLevel(model, (cfg.thinkingLevel ?? "off") as any);
  const history = (cfg.messages ?? prev?.agent.state.messages ?? []).filter(
    (m: any) => m.role !== "system",
  );

  const session: Session = {
    id: cfg.session,
    agent: undefined as unknown as Agent,
    running: false,
  };

  session.agent = new Agent({
    initialState: {
      messages: history,
      systemPrompt: cfg.systemPrompt,
      model,
      thinkingLevel: thinkingLevel as any,
      tools: createPhoneTools(cfg.session, cfg.toolSpecs ?? []),
    },
    sessionId: cfg.session,
    beforeToolCall: async (ctx: BeforeToolCallContext) => {
      const verdict = await bridgeCall("before_tool", {
        session: cfg.session,
        call: ctx.toolCall.id,
        tool: ctx.toolCall.name,
        args: ctx.args,
      });
      if (verdict?.block) {
        return {
          block: true,
          terminate: verdict.terminate !== false,
          reason: String(verdict.reason ?? "操作已取消"),
        };
      }
      return undefined;
    },
    transformContext: pruneOldScreens,
    streamFn: models.streamSimple.bind(models),
  });

  session.agent.subscribe((ev) => forwardEvent(cfg.session, ev));
  sessions.set(cfg.session, session);

  bridge({
    type: "configured",
    session: cfg.session,
    provider: model.provider,
    model: model.id,
    contextWindow: model.contextWindow,
    supportsImages: model.input.includes("image"),
  });
}

async function runPrompt(sessionId: string, text: string) {
  const s = sessions.get(sessionId);
  if (!s) throw new Error(`会话 ${sessionId} 未初始化`);
  if (s.running) {
    s.agent.steer({
      role: "user",
      content: [{ type: "text", text }],
      timestamp: Date.now(),
    } as AgentMessage);
    bridge({ type: "steered", session: sessionId, text });
    return;
  }
  s.running = true;
  try {
    await s.agent.prompt(text);
    while (s.agent.hasQueuedMessages()) {
      await s.agent.continue();
    }
  } finally {
    s.running = false;
    saveTranscript(sessionId);
    bridge({ type: "idle", session: sessionId });
  }
}

function abortSession(sessionId: string) {
  const s = sessions.get(sessionId);
  if (!s) return;
  s.agent.clearAllQueues();
  s.agent.abort();
}

function disposeSession(sessionId: string) {
  const s = sessions.get(sessionId);
  if (!s) return;
  s.agent.abort();
  sessions.delete(sessionId);
}

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
      deviceId = a.deviceId ?? "";
      if (a.credentials) {
        await credentials.load(JSON.parse(a.credentials));
      }
      await syncCustomProviders(a.customProviders ?? [], a.customKeys ?? {});
      await emitStatus();
    }),
  status: () => guard("status", emitStatus),
  syncCustomProviders: (json: string) =>
    guard("syncCustomProviders", async () => {
      const a = arg(json);
      await syncCustomProviders(a.providers ?? [], a.keys ?? {});
      await emitStatus();
    }),
  lookupModels: (json: string) => JSON.stringify(lookupModels(arg(json).ids ?? [])),
  login: (json: string) =>
    guard("login", () => {
      const a = arg(json);
      return startLogin(a.provider, a.type);
    }),
  cancelLogin: () => loginAbort?.abort(),
  answer: (json: string) => {
    const a = arg(json);
    const p = authPrompts.get(a.id);
    if (!p) return;
    authPrompts.delete(a.id);
    if (a.cancel) p.reject(new Error("cancelled"));
    else p.resolve(a.value);
  },
  logout: (json: string) =>
    guard("logout", async () => {
      await models.logout(arg(json).provider);
      await emitStatus();
    }),
  configure: (json: string) => {
    const a = arg(json);
    guard("configure", () => configureSession(a), a.session);
  },
  prompt: (json: string) => {
    const a = arg(json);
    guard("prompt", () => runPrompt(a.session, a.text), a.session);
  },
  abort: (json: string) => abortSession(arg(json).session),
  dispose: (json: string) => disposeSession(arg(json).session),
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
