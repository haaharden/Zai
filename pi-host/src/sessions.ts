import {
  Agent,
  type AgentEvent,
  type AgentMessage,
  type BeforeToolCallContext,
} from "@earendil-works/pi-agent-core";
import { clampThinkingLevel, type Models } from "@earendil-works/pi-ai";
import { bridge, bridgeCall } from "./bridge.ts";
import {
  ARCHIVE_META_ROLE,
  extractSavedArchiveBlocks,
  normalizeCompactionConfig,
  SessionCompactor,
  type CompactionConfig,
} from "./compaction.ts";
import {
  createPhoneTools,
  pruneOldScreens,
  savableTranscript,
  type ToolSpec,
} from "./phone-tools.ts";
import type { QuotaTracker } from "./quota.ts";

export interface ConfigureSessionRequest {
  session: string;
  provider: string;
  modelId: string;
  thinkingLevel?: string;
  systemPrompt: string;
  toolSpecs: ToolSpec[];
  messages?: AgentMessage[];
  compaction?: Partial<CompactionConfig>;
}

interface ActiveSession {
  id: string;
  providerId: string;
  agent: Agent;
  compactor: SessionCompactor;
  running: boolean;
}

export class SessionManager {
  private readonly sessions = new Map<string, ActiveSession>();

  constructor(
    private readonly models: Models,
    private readonly quotaTracker?: QuotaTracker,
  ) {}

  async configure(cfg: ConfigureSessionRequest) {
    const model = this.models.getModel(cfg.provider, cfg.modelId);
    if (!model) {
      throw new Error(`未找到模型 ${cfg.provider}/${cfg.modelId}`);
    }
    const prev = this.sessions.get(cfg.session);
    prev?.agent.abort();

    const thinkingLevel = clampThinkingLevel(model, (cfg.thinkingLevel ?? "off") as any);
    const extracted = cfg.messages
      ? extractSavedArchiveBlocks(cfg.messages as any[])
      : {
          messages: (prev?.agent.state.messages ?? []).filter((m: any) => m.role !== "system"),
          blocks: prev?.compactor.getArchiveBlocks() ?? [],
        };

    const compactionCfg = normalizeCompactionConfig(cfg.compaction);
    const compactor = new SessionCompactor(
      cfg.session,
      this.models,
      model,
      cfg.systemPrompt,
      compactionCfg,
      extracted.blocks,
    );

    let agentRef: Agent | null = null;
    const acpTools = compactor.createAcpTools(
      () => agentRef?.state.messages ?? [],
      (next) => {
        if (agentRef) agentRef.state.messages = next;
      },
      () => this.saveTranscript(cfg.session),
    );
    const allTools = [...createPhoneTools(cfg.session, cfg.toolSpecs ?? []), ...acpTools];

    const agent = new Agent({
      initialState: {
        messages: extracted.messages,
        systemPrompt: cfg.systemPrompt,
        model,
        thinkingLevel: thinkingLevel as any,
        tools: allTools,
      },
      sessionId: cfg.session,
      beforeToolCall: async (ctx: BeforeToolCallContext) => {
        // Native in-process ACP tools never touch the phone screen — bypass Kotlin safety gate
        if (
          ctx.toolCall.name === "context_compress" ||
          ctx.toolCall.name === "context_search" ||
          ctx.toolCall.name === "context_expand"
        ) {
          return undefined;
        }
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
      onResponse: (resp) => {
        if (resp?.headers) {
          this.quotaTracker?.captureFromResponseHeaders(cfg.provider, resp.headers);
        }
      },
      transformContext: async (messages, signal) => {
        try {
          const pruned = await pruneOldScreens(messages);
          return await compactor.maybeAutoCompact(
            pruned,
            (replaced) => {
              agent.state.messages = replaced;
              this.saveTranscript(cfg.session);
            },
            signal,
          );
        } catch {
          return messages;
        }
      },
      transport: "sse",
      streamFn: this.models.streamSimple.bind(this.models),
    });
    agentRef = agent;

    agent.subscribe((ev) => this.forwardEvent(cfg.session, ev));
    this.sessions.set(cfg.session, {
      id: cfg.session,
      providerId: cfg.provider,
      agent,
      compactor,
      running: false,
    });

    bridge({
      type: "configured",
      session: cfg.session,
      provider: model.provider,
      model: model.id,
      contextWindow: model.contextWindow,
      supportsImages: model.input.includes("image"),
    });
    compactor.emitUsage(extracted.messages);
  }

  async runPrompt(sessionId: string, text: string) {
    const s = this.sessions.get(sessionId);
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
      this.saveTranscript(sessionId);
      s.compactor.emitUsage(s.agent.state.messages);
      bridge({ type: "idle", session: sessionId });
    }
  }

  async compactNow(sessionId: string) {
    const s = this.sessions.get(sessionId);
    if (!s || s.running) return;
    const pruned = await pruneOldScreens(s.agent.state.messages);
    const res = await s.compactor.compactMessages(pruned);
    if (res) {
      s.agent.state.messages = res.messages;
      this.saveTranscript(sessionId);
      s.compactor.emitUsage(res.messages);
    } else {
      s.compactor.emitUsage(s.agent.state.messages);
    }
  }

  abort(sessionId: string) {
    const s = this.sessions.get(sessionId);
    if (!s) return;
    s.agent.clearAllQueues();
    s.agent.abort();
  }

  dispose(sessionId: string) {
    const s = this.sessions.get(sessionId);
    if (!s) return;
    s.agent.abort();
    this.sessions.delete(sessionId);
  }

  private forwardEvent(sessionId: string, event: AgentEvent) {
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
          const s = this.sessions.get(sessionId);
          const usage = m.usage;
          if (s && usage) {
            const actualIn = Number(usage.input ?? 0) + Number(usage.cacheRead ?? 0);
            if (actualIn > 0) {
              s.compactor.recordActualInputTokens(actualIn, s.agent.state.messages.length);
              s.compactor.emitUsage(s.agent.state.messages);
            }
          }
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
        this.saveTranscript(sessionId);
        break;
      default:
        break;
    }
  }

  private saveTranscript(sessionId: string) {
    const s = this.sessions.get(sessionId);
    if (s) {
      const cleaned = savableTranscript(s.agent.state.messages) as any[];
      const blocks = s.compactor.getArchiveBlocks();
      if (blocks.length > 0) {
        cleaned.push({ role: ARCHIVE_META_ROLE, blocks });
      }
      bridge({
        type: "transcript",
        session: sessionId,
        messages: cleaned,
      });
    }
  }
}
