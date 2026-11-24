import {
  createModels,
  createProvider,
  envApiKeyAuth,
  getSupportedThinkingLevels,
  type Model,
} from "@earendil-works/pi-ai";
import { openaiCodexProvider } from "@earendil-works/pi-ai/providers/openai-codex";
import { openaiProvider } from "@earendil-works/pi-ai/providers/openai";
import { deepseekProvider } from "@earendil-works/pi-ai/providers/deepseek";
import { anthropicProvider } from "@earendil-works/pi-ai/providers/anthropic";
import { googleProvider } from "@earendil-works/pi-ai/providers/google";
import { openrouterProvider } from "@earendil-works/pi-ai/providers/openrouter";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { openAIResponsesApi } from "@earendil-works/pi-ai/api/openai-responses.lazy";
import { anthropicMessagesApi } from "@earendil-works/pi-ai/api/anthropic-messages.lazy";
import type { CredentialStore } from "@earendil-works/pi-ai";

export const PRIMARY_OAUTH_PROVIDER_ID = "openai-codex";
export const OAUTH_PROVIDERS = new Set([PRIMARY_OAUTH_PROVIDER_ID]);
const DEFAULT_CONTEXT = 128_000;

export interface CustomModelConfig {
  id: string;
  contextWindow?: number;
  images?: boolean;
  reasoning?: boolean;
  efforts?: string[];
  api?: "openai-completions" | "openai-responses" | "anthropic-messages";
}

export interface CustomProviderConfig {
  id: string;
  name: string;
  baseUrl: string;
  models: CustomModelConfig[];
  images: boolean;
}

const normalizeModelId = (id: string) =>
  id.toLowerCase().replace(/^.*\//, "").replace(/:.*$/, "").replace(/^~/, "").replace(/[._]/g, "-");

function apiBaseUrl(baseUrl: string, api: string): string {
  const clean = baseUrl.trim().replace(/\/+$/, "");
  return api === "anthropic-messages" ? clean.replace(/\/v1$/, "") : clean;
}

function thinkingMapFromEfforts(efforts?: string[]) {
  if (!efforts?.length) return undefined;
  const set = new Set(efforts.map((e) => e.toLowerCase()));
  const map: Record<string, string | null> = {
    off: set.has("none") ? "none" : null,
  };
  for (const lvl of ["minimal", "low", "medium", "high", "xhigh", "max"]) {
    map[lvl] = set.has(lvl) ? lvl : null;
  }
  return map;
}

export class ProviderRegistry {
  private readonly customIds = new Set<string>();

  constructor(
    private readonly models: ReturnType<typeof createModels>,
    private readonly credentials: CredentialStore,
  ) {}

  registerBuiltins() {
    for (const provider of [
      openaiCodexProvider(),
      openaiProvider(),
      deepseekProvider(),
      anthropicProvider(),
      googleProvider(),
      openrouterProvider(),
    ]) {
      this.models.setProvider(provider);
    }
  }

  catalogModel(id: string): Model<any> | undefined {
    const builtIn = this.models.getProviders().filter((p) => !this.customIds.has(p.id));
    const want = normalizeModelId(id);
    for (const p of builtIn) {
      const ms = this.models.getModels(p.id);
      const hit = ms.find((x) => x.id === id) ?? ms.find((x) => normalizeModelId(x.id) === want);
      if (hit) return hit;
    }
    return undefined;
  }

  lookupModels(ids: string[]) {
    const out: Record<string, { contextWindow: number; images: boolean; reasoning: boolean }> = {};
    for (const id of ids) {
      const m = this.catalogModel(id);
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

  async syncCustomProviders(list: CustomProviderConfig[], keys: Record<string, string>) {
    const keep = new Set(list.map((c) => c.id));
    for (const id of [...this.customIds]) {
      if (!keep.has(id)) {
        this.models.deleteProvider(id);
        await this.credentials.delete(id);
        this.customIds.delete(id);
      }
    }
    for (const cfg of list) {
      if (cfg.models.length === 0) continue;
      this.models.setProvider(this.buildCustomProvider(cfg));
      this.customIds.add(cfg.id);
      const key = keys[cfg.id];
      if (key) {
        await this.credentials.modify(cfg.id, async () => ({ type: "api_key", key }));
      }
    }
  }

  async buildVisibleStatus() {
    const infos = await this.credentials.list();
    const visibleIds = new Set<string>([PRIMARY_OAUTH_PROVIDER_ID, ...this.customIds]);
    return this.models
      .getProviders()
      .filter((p) => visibleIds.has(p.id))
      .map((p) => {
        const credType = infos.find((i) => i.providerId === p.id)?.type ?? null;
        return {
          id: p.id,
          name: p.id === PRIMARY_OAUTH_PROVIDER_ID ? "ChatGPT Codex" : p.name,
          credential: credType,
          custom: this.customIds.has(p.id),
          supportsOAuth: OAUTH_PROVIDERS.has(p.id) && !!p.auth.oauth,
          models: this.modelsFor(p.id),
        };
      });
  }

  private modelsFor(providerId: string) {
    const list = this.models.getModels(providerId).map((m) => ({
      id: m.id,
      name: m.name,
      thinkingLevels: getSupportedThinkingLevels(m),
      contextWindow: m.contextWindow,
      input: m.input,
    }));
    // Catalog is ordered oldest -> newest; show newest flagship Codex models first
    return providerId === PRIMARY_OAUTH_PROVIDER_ID ? list.reverse() : list;
  }

  private buildCustomProvider(cfg: CustomProviderConfig) {
    return createProvider({
      id: cfg.id,
      name: cfg.name,
      baseUrl: cfg.baseUrl,
      auth: { apiKey: envApiKeyAuth(`${cfg.name} API key`, []) },
      models: cfg.models.map((m) => {
        const api = m.api ?? "openai-completions";
        const known = this.catalogModel(m.id);
        const supportsImages = m.images ?? known?.input.includes("image") ?? cfg.images;
        const customMap = thinkingMapFromEfforts(m.efforts);
        const reasoning = m.reasoning || !!m.efforts?.length || !!known?.reasoning;
        return {
          id: m.id,
          name: m.id,
          api,
          provider: cfg.id,
          baseUrl: apiBaseUrl(cfg.baseUrl, api),
          reasoning,
          thinkingLevelMap: customMap ?? known?.thinkingLevelMap,
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
}
