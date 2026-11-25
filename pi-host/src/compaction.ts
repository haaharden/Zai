import type { AgentMessage, AgentTool } from "@earendil-works/pi-agent-core";
import type { Api, Model, Models } from "@earendil-works/pi-ai";
import {
  ACP_STATUS_TOOL_NAME,
  activeBlocks,
  baseIdOf,
  blockDocs,
  buildCompressSystemPrompt,
  buildStatusReport,
  collectBlockContent,
  COMPRESS_PARAMETERS,
  COMPRESS_TOOL_NAME,
  createContentStore,
  createCore,
  createInitialState,
  DECOMPRESS_TOOL_NAME,
  DEFAULT_CCR_CONFIG,
  defaultConfig,
  isStoredPlaceholderText,
  isSummaryMessageId,
  leanHowToCompress,
  leanPack,
  markBlockRestoredInline,
  messageDocs,
  parseBlockIdArg,
  parseCompressArgs,
  renderNudgeText,
  resolvePrompts,
  RETRIEVE_TOOL_DESCRIPTION,
  RETRIEVE_TOOL_NAME,
  SEARCH_CONTEXT_TOOL_NAME,
  searchBlocks,
  type CompressionCore,
  type CompressionState,
  type Config as AcpConfig,
  type CoreMessage,
  type MessageContentStore,
  type Prompts,
} from "acp-kernel";
import { bridge } from "./bridge.ts";

export type CompactionMode = "off" | "auto" | "acp";

export interface CompactionConfig {
  mode: CompactionMode;
  thresholdPercent: number; // 40..95, default 75
}

export interface PersistedCompactionState {
  version: 2;
  engine: "acp-kernel";
  state: CompressionState;
  contentStore: MessageContentStore;
  nextMsgSeq: number;
}

export interface ContextUsageSnapshot {
  estimatedTokens: number;
  contextWindow: number;
  percent: number;
  archivedBlocks: number;
  mode: CompactionMode;
  thresholdPercent: number;
}

export const ARCHIVE_META_ROLE = "zai_archive_meta";
export const ACP_KERNEL_TOOL_NAMES = new Set<string>([
  COMPRESS_TOOL_NAME,
  DECOMPRESS_TOOL_NAME,
  SEARCH_CONTEXT_TOOL_NAME,
  ACP_STATUS_TOOL_NAME,
  RETRIEVE_TOOL_NAME,
]);

const MIN_MESSAGES_TO_COMPACT = 6;
const DEFAULT_KEEP_RECENT_MESSAGES = 4;
const MAX_EXCERPT_CHARS_FOR_SUMMARY = 18_000;

export function normalizeCompactionConfig(raw?: Partial<CompactionConfig>): CompactionConfig {
  const mode: CompactionMode =
    raw?.mode === "off" || raw?.mode === "auto" || raw?.mode === "acp" ? raw.mode : "acp";
  const pct = Number(raw?.thresholdPercent ?? 75);
  const thresholdPercent = Number.isFinite(pct) ? Math.max(40, Math.min(95, Math.round(pct))) : 75;
  return { mode, thresholdPercent };
}

/**
 * Fast bilingual (CJK + Latin) token estimator injected into acp-kernel's `createCore({ countTokens })`.
 * CJK characters average ~1.5 chars/token; ASCII/Latin averages ~4 chars/token.
 */
export function estimateTextTokens(text: string): number {
  if (!text) return 0;
  let cjk = 0;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (
      (code >= 0x3000 && code <= 0x9fff) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xff00 && code <= 0xffef)
    ) {
      cjk++;
    }
  }
  const other = text.length - cjk;
  return Math.ceil(cjk / 1.5 + other / 4);
}

export function extractMessagePlainText(msg: any): string {
  if (!msg) return "";
  if (typeof msg.content === "string") return msg.content;
  if (!Array.isArray(msg.content)) return "";
  const parts: string[] = [];
  for (const part of msg.content) {
    if (!part) continue;
    if (part.type === "text" && typeof part.text === "string") {
      parts.push(part.text);
    } else if (part.type === "toolCall") {
      const argsStr = part.arguments ? JSON.stringify(part.arguments).slice(0, 400) : "";
      parts.push(`[call ${part.name}(${argsStr})]`);
    } else if (part.type === "image") {
      parts.push("[image]");
    }
  }
  return parts.join("\n");
}

function extractThinkingTokens(msg: any): number {
  if (!msg || !Array.isArray(msg.content)) return 0;
  let total = 0;
  for (const part of msg.content) {
    if (part?.type === "thinking" && typeof part.thinking === "string") {
      total += estimateTextTokens(part.thinking);
    }
  }
  return total;
}

export function estimateMessageTokens(msg: any): number {
  if (!msg) return 0;
  let tokens = 12;
  if (typeof msg.content === "string") {
    return tokens + estimateTextTokens(msg.content);
  }
  if (Array.isArray(msg.content)) {
    for (const part of msg.content) {
      if (!part) continue;
      if (part.type === "text" && typeof part.text === "string") {
        tokens += estimateTextTokens(part.text);
      } else if (part.type === "thinking" && typeof part.thinking === "string") {
        tokens += estimateTextTokens(part.thinking);
      } else if (part.type === "toolCall") {
        tokens +=
          24 +
          estimateTextTokens(String(part.name ?? "")) +
          estimateTextTokens(JSON.stringify(part.arguments ?? {}));
      } else if (part.type === "image") {
        tokens += 1200;
      }
    }
  }
  return tokens;
}

export function estimateConversationTokens(messages: readonly AgentMessage[], systemPrompt: string): number {
  let total = estimateTextTokens(systemPrompt) + 250;
  for (const m of messages) {
    total += estimateMessageTokens(m);
  }
  return total;
}

/**
 * Finds a safe split index `[0..splitIdx)` for non-ACP automatic compaction without breaking tool pairs.
 */
export function findSafeSplitIndex(
  messages: readonly AgentMessage[],
  targetKeepTokens: number,
  minKeepMessages: number = DEFAULT_KEEP_RECENT_MESSAGES,
): number {
  if (messages.length <= minKeepMessages) return 0;

  let keptTokens = 0;
  let candidate = messages.length;
  for (let i = messages.length - 1; i >= 0; i--) {
    const countKept = messages.length - i;
    keptTokens += estimateMessageTokens(messages[i]);
    candidate = i;
    if (countKept >= minKeepMessages && keptTokens >= targetKeepTokens) {
      break;
    }
  }

  while (candidate > 0 && candidate < messages.length) {
    const m: any = messages[candidate];
    const prev: any = messages[candidate - 1];
    const startsOnToolResult = m?.role === "toolResult";
    const prevHasToolCall =
      prev?.role === "assistant" &&
      Array.isArray(prev.content) &&
      prev.content.some((c: any) => c?.type === "toolCall");
    if (!startsOnToolResult && !prevHasToolCall) {
      break;
    }
    candidate--;
  }

  return candidate >= 2 ? candidate : 0;
}

function formatMessagesExcerpt(messages: readonly AgentMessage[]): string {
  const lines: string[] = [];
  for (const raw of messages as any[]) {
    const roleLabel =
      raw.role === "user"
        ? "用户"
        : raw.role === "assistant"
          ? "助手"
          : raw.role === "toolResult"
            ? `工具(${raw.toolName ?? "tool"})`
            : String(raw.role ?? "msg");
    const body = extractMessagePlainText(raw).trim();
    if (!body) continue;
    lines.push(`[${roleLabel}] ${body.slice(0, 1800)}`);
  }
  const joined = lines.join("\n\n");
  return joined.length > MAX_EXCERPT_CHARS_FOR_SUMMARY
    ? `${joined.slice(0, MAX_EXCERPT_CHARS_FOR_SUMMARY)}\n...[已截断]`
    : joined;
}

function buildExtractiveFallbackSummary(messages: readonly AgentMessage[]): string {
  const snippets: string[] = [];
  for (const raw of messages as any[]) {
    if (raw.role !== "user" && raw.role !== "assistant") continue;
    const text = extractMessagePlainText(raw).replace(/\s+/g, " ").trim();
    if (!text) continue;
    const prefix = raw.role === "user" ? "用户" : "助手";
    snippets.push(`- ${prefix}：${text.slice(0, 180)}`);
    if (snippets.length >= 10) break;
  }
  return snippets.length > 0 ? snippets.join("\n") : "早期对话记录已归档压缩。";
}

/**
 * Strips persisted ACP metadata out of restored transcript messages so the LLM never receives it directly.
 */
export function extractSavedCompactionState(rawMessages?: any[]): {
  messages: AgentMessage[];
  persisted: PersistedCompactionState | null;
} {
  if (!Array.isArray(rawMessages) || rawMessages.length === 0) {
    return { messages: [], persisted: null };
  }
  let persisted: PersistedCompactionState | null = null;
  const clean: AgentMessage[] = [];
  for (const m of rawMessages) {
    if (m && m.role === ARCHIVE_META_ROLE) {
      if (m.version === 2 && m.engine === "acp-kernel" && m.state && typeof m.state === "object") {
        persisted = {
          version: 2,
          engine: "acp-kernel",
          state: m.state as CompressionState,
          contentStore:
            m.contentStore && typeof m.contentStore === "object" && m.contentStore.byRef
              ? (m.contentStore as MessageContentStore)
              : createContentStore(),
          nextMsgSeq: Number(m.nextMsgSeq) || 0,
        };
      }
      continue;
    }
    if (m && m.role !== "system") {
      clean.push(m);
    }
  }
  return { messages: clean, persisted };
}

function cloneWithPrefixTag(orig: any, tagPrefix: string): any {
  if (!tagPrefix) return orig;
  if (typeof orig.content === "string") {
    return { ...orig, content: `${tagPrefix}${orig.content}` };
  }
  if (Array.isArray(orig.content)) {
    let injected = false;
    const nextContent = orig.content.map((part: any) => {
      if (!injected && part?.type === "text" && typeof part.text === "string") {
        injected = true;
        return { ...part, text: `${tagPrefix}${part.text}` };
      }
      return part;
    });
    if (!injected) {
      nextContent.unshift({ type: "text", text: tagPrefix.trimEnd() });
    }
    return { ...orig, content: nextContent };
  }
  return orig;
}

function replaceToolResultText(orig: any, nextText: string): any {
  if (typeof orig.content === "string") {
    return { ...orig, content: nextText };
  }
  if (Array.isArray(orig.content)) {
    const nonText = orig.content.filter((p: any) => p?.type !== "text");
    return { ...orig, content: [{ type: "text", text: nextText }, ...nonText] };
  }
  return orig;
}

function extractAcpTagPrefix(renderedText?: string): string {
  if (!renderedText || !renderedText.startsWith("<acp ")) return "";
  const endIdx = renderedText.indexOf("</acp>");
  if (endIdx < 0) return "";
  const after = endIdx + "</acp>".length;
  return renderedText[after] === "\n" ? renderedText.slice(0, after + 1) : `${renderedText.slice(0, after)}\n`;
}

/**
 * Ensures projected Pi messages never contain an orphaned `toolResult` whose matching `toolCall`
 * was pruned or truncated, preventing HTTP 400 errors on Anthropic / OpenAI / Codex.
 */
export function sanitizeToolPairs(messages: AgentMessage[]): AgentMessage[] {
  const availableCallIds = new Set<string>();
  const out: AgentMessage[] = [];
  for (const raw of messages as any[]) {
    if (raw.role === "assistant" && Array.isArray(raw.content)) {
      for (const p of raw.content) {
        if (p?.type === "toolCall" && typeof p.id === "string") {
          availableCallIds.add(p.id);
        }
      }
      out.push(raw);
      continue;
    }
    if (raw.role === "toolResult") {
      const cid = typeof raw.toolCallId === "string" ? raw.toolCallId : "";
      if (cid && !availableCallIds.has(cid)) {
        continue;
      }
      out.push(raw);
      continue;
    }
    out.push(raw);
  }
  return out;
}

function appendNudgeToMessages(messages: AgentMessage[], nudgeText: string): AgentMessage[] {
  if (!nudgeText.trim()) return messages;
  const copy = [...messages];
  for (let i = copy.length - 1; i >= 0; i--) {
    const m: any = copy[i];
    if (m.role === "user") {
      if (typeof m.content === "string") {
        copy[i] = { ...m, content: `${m.content}\n\n${nudgeText}` } as AgentMessage;
      } else if (Array.isArray(m.content)) {
        copy[i] = {
          ...m,
          content: [...m.content, { type: "text", text: `\n\n${nudgeText}` }],
        } as AgentMessage;
      }
      return copy;
    }
  }
  copy.push({
    role: "user",
    content: [{ type: "text", text: nudgeText }],
    timestamp: Date.now(),
  } as AgentMessage);
  return copy;
}

/**
 * Per-session Context Compaction Engine powered by the official `acp-kernel` (billion-context core)
 * in ACP mode, plus clean automatic threshold compaction in AUTO mode.
 */
export class SessionCompactor {
  private readonly core: CompressionCore;
  private readonly acpConfig: AcpConfig;
  private readonly prompts: Prompts;
  private acpState: CompressionState;
  private contentStore: MessageContentStore;
  private nextMsgSeq: number;
  private lastReportedInputTokens = 0;
  private lastReportedMsgCount = 0;
  private lastEffectiveTokens = 0;
  private isCompacting = false;

  constructor(
    private readonly sessionId: string,
    private readonly models: Models,
    private readonly model: Model<Api>,
    private readonly systemPrompt: string,
    public readonly config: CompactionConfig,
    persisted: PersistedCompactionState | null = null,
  ) {
    this.core = createCore({ countTokens: estimateTextTokens });
    const contextWindow = Math.max(4_096, this.model.contextWindow || 128_000);
    const maxPct = Math.max(0.4, Math.min(0.95, this.config.thresholdPercent / 100));
    const minPct = Math.max(0.25, maxPct - 0.25);

    this.acpConfig = defaultConfig(contextWindow, {
      tiers: {
        enabled: true,
        tier2Trigger: 5,
        tier3Trigger: 10,
      },
      nudge: {
        maxContextLimitPct: maxPct,
        minContextLimitPct: minPct,
        frequency: 1,
        iterationThreshold: 1,
        force: "soft",
        growthRatio: 0.05,
        growthFloor: Math.min(4_000, Math.round(contextWindow * 0.04)),
        growthCap: 40_000,
        minGrowthFloor: Math.min(2_500, Math.round(contextWindow * 0.025)),
        minGrowthRatio: 0.45,
        emergencyThresholdPct: Math.min(0.96, maxPct + 0.15),
        tier2GrowthMultiplier: 1.5,
      },
      preserveRecentMessages: 5,
      preserveRecentTokens: Math.min(8_000, Math.round(contextWindow * 0.12)),
      compress: {
        minCompressRange: 600,
        minSummaryLength: 40,
        maxSummaryLength: 20_000,
      },
      protectedTools: [...ACP_KERNEL_TOOL_NAMES],
      ccr: {
        ...DEFAULT_CCR_CONFIG,
        enabled: true,
        toolName: RETRIEVE_TOOL_NAME,
        minToolTokens: 1_500,
        excludeTools: [...ACP_KERNEL_TOOL_NAMES],
      },
    });

    this.prompts = resolvePrompts(undefined, { languagePreservation: true });
    this.acpState = persisted?.state ?? createInitialState();
    this.contentStore = persisted?.contentStore ?? createContentStore();
    this.nextMsgSeq = persisted?.nextMsgSeq ?? 0;
  }

  /**
   * Returns the official `acp-kernel` lean system prompt section when ACP mode is active.
   */
  getSystemPromptAddition(): string {
    if (this.config.mode !== "acp") return "";
    const leanSections = leanPack.surface.promptSections;
    const basePrompt = buildCompressSystemPrompt(
      {
        ...this.prompts,
        howToCompressRules: leanHowToCompress(true),
      },
      leanSections,
    );
    return `\n\n${basePrompt}`;
  }

  serializePersistedState(): PersistedCompactionState | null {
    if (this.config.mode !== "acp") return null;
    const hasData =
      this.acpState.blocks.length > 0 ||
      Object.keys(this.acpState.messageRefs.byRaw).length > 0 ||
      Object.keys(this.contentStore.byRef).length > 0;
    if (!hasData) return null;
    return {
      version: 2,
      engine: "acp-kernel",
      state: this.acpState,
      contentStore: this.contentStore,
      nextMsgSeq: this.nextMsgSeq,
    };
  }

  getActiveBlockCount(): number {
    return activeBlocks(this.acpState).length;
  }

  recordActualInputTokens(inputTokens: number, messageCount: number) {
    if (inputTokens > 0) {
      this.lastReportedInputTokens = inputTokens;
      this.lastReportedMsgCount = messageCount;
    }
  }

  /**
   * Stamps a stable immutable `_acpId` onto every Pi message so `acp-kernel` `mNNNNN` refs
   * remain stable across turns and across app restarts.
   */
  ensureStableIds(messages: readonly AgentMessage[]): Map<string, AgentMessage> {
    const map = new Map<string, AgentMessage>();
    for (const raw of messages as any[]) {
      if (typeof raw._acpId !== "string" || !raw._acpId) {
        raw._acpId = `z_${++this.nextMsgSeq}`;
      }
      map.set(raw._acpId, raw);
    }
    return map;
  }

  toCoreMessages(messages: readonly AgentMessage[]): CoreMessage[] {
    this.ensureStableIds(messages);
    return (messages as any[]).map((raw): CoreMessage => {
      const id = String(raw._acpId);
      if (raw.role === "user") {
        return {
          id,
          role: "user",
          contentType: "text",
          text: extractMessagePlainText(raw),
        };
      }
      if (raw.role === "toolResult") {
        return {
          id,
          role: "tool",
          contentType: "tool-result",
          toolName: typeof raw.toolName === "string" ? raw.toolName : undefined,
          toolCallId: typeof raw.toolCallId === "string" ? raw.toolCallId : undefined,
          text: extractMessagePlainText(raw),
        };
      }
      const parts = Array.isArray(raw.content) ? raw.content : [];
      const firstCall = parts.find((p: any) => p?.type === "toolCall");
      const thinkingTokens = extractThinkingTokens(raw);
      return {
        id,
        role: "assistant",
        contentType: firstCall ? "tool-call" : "text",
        toolName: firstCall?.name,
        toolCallId: firstCall?.id,
        text: extractMessagePlainText(raw),
        ...(thinkingTokens > 0 ? { thinkingTokens } : {}),
      };
    });
  }

  computeUsage(messages: readonly AgentMessage[]): ContextUsageSnapshot {
    const rawEstimated = estimateConversationTokens(messages, this.systemPrompt);
    const activeBase =
      this.config.mode === "acp" && this.lastEffectiveTokens > 0 && this.acpState.blocks.length > 0
        ? Math.min(rawEstimated, this.lastEffectiveTokens)
        : rawEstimated;
    const effective =
      this.lastReportedInputTokens > 0 && messages.length >= this.lastReportedMsgCount
        ? Math.max(activeBase, this.lastReportedInputTokens)
        : activeBase;
    const contextWindow = Math.max(4_096, this.model.contextWindow || 128_000);
    const percent = Math.max(0, Math.min(100, Math.round((effective / contextWindow) * 100)));
    return {
      estimatedTokens: effective,
      contextWindow,
      percent,
      archivedBlocks: this.getActiveBlockCount(),
      mode: this.config.mode,
      thresholdPercent: this.config.thresholdPercent,
    };
  }

  emitUsage(messages: readonly AgentMessage[]) {
    const usage = this.computeUsage(messages);
    bridge({
      type: "context_usage",
      session: this.sessionId,
      ...usage,
    });
    return usage;
  }

  /**
   * Called from `Agent.transformContext` before every LLM request.
   * Must NEVER throw or reject.
   */
  async transformMessages(
    messages: AgentMessage[],
    onAutoReplaced: (nextMessages: AgentMessage[]) => void,
    signal?: AbortSignal,
  ): Promise<AgentMessage[]> {
    if (this.config.mode === "off") {
      this.emitUsage(messages);
      return messages;
    }

    if (this.config.mode === "auto") {
      const usage = this.emitUsage(messages);
      if (this.isCompacting || messages.length < MIN_MESSAGES_TO_COMPACT) return messages;
      if (usage.percent < this.config.thresholdPercent) return messages;
      const compacted = await this.runAutoSummarizeCompaction(messages, signal);
      if (!compacted) return messages;
      onAutoReplaced(compacted.messages);
      this.emitUsage(compacted.messages);
      return compacted.messages;
    }

    // Official `acp-kernel` (billion-context) turn pipeline
    return this.runAcpKernelTurn(messages, signal);
  }

  private async runAcpKernelTurn(
    messages: AgentMessage[],
    signal?: AbortSignal,
  ): Promise<AgentMessage[]> {
    const rawMap = this.ensureStableIds(messages);
    const coreMessages = this.toCoreMessages(messages);
    const estimatedBefore = estimateConversationTokens(messages, this.systemPrompt);
    const tokenInput =
      this.lastReportedInputTokens > 0 && messages.length >= this.lastReportedMsgCount
        ? Math.max(estimatedBefore, this.lastReportedInputTokens)
        : estimatedBefore;

    let turnResult = this.core.processTurn({
      messages: coreMessages,
      state: this.acpState,
      config: this.acpConfig,
      tokenCount: tokenInput,
      renderTags: "text-only",
      contentStore: this.contentStore,
    });
    this.acpState = turnResult.state;
    this.contentStore = turnResult.contentStore;

    let projected = this.projectCoreToAgentMessages(turnResult.messages, rawMap);
    this.lastEffectiveTokens = estimateConversationTokens(projected, this.systemPrompt);
    const usage = this.emitUsage(messages);

    // Emergency auto-fallback if model ignored nudges and usage hit extreme ceiling
    const emergencyCeiling = Math.min(95, Math.max(this.config.thresholdPercent + 12, 88));
    if (!this.isCompacting && usage.percent >= emergencyCeiling && messages.length >= MIN_MESSAGES_TO_COMPACT) {
      const committed = await this.compactOldestIntoAcpKernel(messages, signal);
      if (committed) {
        turnResult = this.core.processTurn({
          messages: this.toCoreMessages(messages),
          state: this.acpState,
          config: this.acpConfig,
          tokenCount: this.lastEffectiveTokens,
          renderTags: "text-only",
          contentStore: this.contentStore,
        });
        this.acpState = turnResult.state;
        this.contentStore = turnResult.contentStore;
        projected = this.projectCoreToAgentMessages(turnResult.messages, rawMap);
        this.lastEffectiveTokens = estimateConversationTokens(projected, this.systemPrompt);
        this.emitUsage(messages);
      }
    }

    // Inject kernel's official growth/pressure/tier nudge cleanly onto the trailing user message
    if (turnResult.nudge?.shouldInject) {
      const rendered = renderNudgeText(
        turnResult.nudge,
        this.prompts,
        leanPack.surface.nudgeSections,
      );
      if (rendered?.text) {
        projected = appendNudgeToMessages(projected, rendered.text);
      }
    }

    return sanitizeToolPairs(projected);
  }

  private projectCoreToAgentMessages(
    coreMessages: CoreMessage[],
    rawMap: Map<string, AgentMessage>,
  ): AgentMessage[] {
    const out: AgentMessage[] = [];
    for (const cm of coreMessages) {
      if (isSummaryMessageId(cm.id)) {
        out.push({
          role: "user",
          content: [{ type: "text", text: cm.text ?? "[Compressed conversation section]" }],
          timestamp: Date.now(),
        } as AgentMessage);
        continue;
      }

      const rawId = baseIdOf(cm.id);
      const orig = rawMap.get(rawId) as any;
      if (!orig) {
        if (cm.text) {
          out.push({
            role: "user",
            content: [{ type: "text", text: cm.text }],
            timestamp: Date.now(),
          } as AgentMessage);
        }
        continue;
      }

      if (orig.role === "toolResult" && cm.text && isStoredPlaceholderText(cm.text)) {
        out.push(replaceToolResultText(orig, cm.text));
        continue;
      }

      const tagPrefix = extractAcpTagPrefix(cm.text);
      out.push(tagPrefix ? cloneWithPrefixTag(orig, tagPrefix) : orig);
    }
    return out;
  }

  /**
   * Manual compaction triggered by the user from the UI context sheet.
   * - In ACP mode: summarizes oldest compressible messages and commits a real `acp-kernel` block (bN).
   * - In AUTO / OFF mode: summarizes older prefix cleanly in place.
   */
  async manualCompact(
    messages: AgentMessage[],
    onAutoReplaced: (nextMessages: AgentMessage[]) => void,
    saveTranscript: () => void,
    signal?: AbortSignal,
  ): Promise<boolean> {
    if (this.isCompacting || messages.length < 4) return false;
    if (this.config.mode === "acp") {
      const ok = await this.compactOldestIntoAcpKernel(messages, signal);
      if (ok) {
        saveTranscript();
        this.emitUsage(messages);
      }
      return ok;
    }
    const res = await this.runAutoSummarizeCompaction(messages, signal);
    if (!res) return false;
    onAutoReplaced(res.messages);
    saveTranscript();
    this.emitUsage(res.messages);
    return true;
  }

  private async compactOldestIntoAcpKernel(
    messages: AgentMessage[],
    signal?: AbortSignal,
  ): Promise<boolean> {
    if (this.isCompacting) return false;
    this.isCompacting = true;
    try {
      const coreMessages = this.toCoreMessages(messages);
      const beforeTokens = this.computeUsage(messages).estimatedTokens;
      // Ensure every message has an assigned mNNNNN ref first
      const prep = this.core.processTurn({
        messages: coreMessages,
        state: this.acpState,
        config: this.acpConfig,
        tokenCount: beforeTokens,
        renderTags: "none",
        contentStore: this.contentStore,
      });
      this.acpState = prep.state;
      this.contentStore = prep.contentStore;

      // Pick the oldest compressible slice outside the protected recent tail (skip index 0 if user prompt)
      const preserveCount = Math.min(this.acpConfig.preserveRecentMessages, Math.max(2, messages.length - 2));
      const endExclusive = messages.length - preserveCount;
      if (endExclusive < 2) return false;

      // Collect non-covered core messages in [0..endExclusive)
      const covered = new Set<string>();
      for (const blk of activeBlocks(this.acpState)) {
        for (const id of blk.effectiveMessageIds) covered.add(id);
      }
      const candidateIndices: number[] = [];
      for (let i = 0; i < endExclusive; i++) {
        const cm = coreMessages[i];
        if (cm && !covered.has(cm.id) && this.acpState.messageRefs.byRaw[cm.id]) {
          candidateIndices.push(i);
        }
      }
      // If first message is the initial user prompt and there are >= 2 others, skip it (kernel protects index 0)
      if (candidateIndices.length >= 3 && candidateIndices[0] === 0 && coreMessages[0]?.role === "user") {
        candidateIndices.shift();
      }
      if (candidateIndices.length < 2) return false;

      const startIdx = candidateIndices[0];
      const endIdx = candidateIndices[candidateIndices.length - 1];
      const startRef = this.acpState.messageRefs.byRaw[coreMessages[startIdx].id];
      const endRef = this.acpState.messageRefs.byRaw[coreMessages[endIdx].id];
      if (!startRef || !endRef) return false;

      const slice = messages.slice(startIdx, endIdx + 1);
      const excerpt = formatMessagesExcerpt(slice);
      let summary = await this.generateSummaryWithModel(excerpt, signal);
      if (!summary || summary.length < 40) {
        summary = buildExtractiveFallbackSummary(slice);
      }

      const applied = this.core.applyCompression({
        ranges: [{ startRef, endRef, summary }],
        messages: coreMessages,
        state: this.acpState,
        config: {
          ...this.acpConfig,
          preserveRecentMessages: Math.min(4, Math.max(2, messages.length - 4)),
          preserveRecentTokens: 0,
          compress: { ...this.acpConfig.compress, minCompressRange: 0, minSummaryLength: 20 },
        },
      });
      if (applied.result.blocksCreated <= 0) return false;

      this.acpState = applied.state;
      this.lastReportedInputTokens = 0;
      this.lastReportedMsgCount = 0;

      const afterTurn = this.core.processTurn({
        messages: coreMessages,
        state: this.acpState,
        config: this.acpConfig,
        tokenCount: Math.max(0, beforeTokens - applied.result.tokensCompressed),
        renderTags: "none",
        contentStore: this.contentStore,
      });
      this.acpState = afterTurn.state;
      const rawMap = this.ensureStableIds(messages);
      const projected = this.projectCoreToAgentMessages(afterTurn.messages, rawMap);
      const afterTokens = estimateConversationTokens(projected, this.systemPrompt);
      this.lastEffectiveTokens = afterTokens;
      const contextWindow = Math.max(4_096, this.model.contextWindow || 128_000);
      const percent = Math.max(0, Math.min(100, Math.round((afterTokens / contextWindow) * 100)));
      const newestBlock = this.acpState.blocks[this.acpState.blocks.length - 1];

      bridge({
        type: "context_compacted",
        session: this.sessionId,
        blockId: newestBlock?.blockId ?? "b1",
        foldedCount: slice.length,
        beforeTokens,
        afterTokens,
        percent,
      });
      return true;
    } catch {
      return false;
    } finally {
      this.isCompacting = false;
    }
  }

  private async runAutoSummarizeCompaction(
    messages: AgentMessage[],
    signal?: AbortSignal,
  ): Promise<{ messages: AgentMessage[]; beforeTokens: number; afterTokens: number } | null> {
    if (this.isCompacting) return null;
    this.isCompacting = true;
    try {
      const beforeTokens = this.computeUsage(messages).estimatedTokens;
      const contextWindow = Math.max(4_096, this.model.contextWindow || 128_000);
      const keepTokens = Math.min(
        Math.round(contextWindow * 0.25),
        Math.max(4_000, Math.round(beforeTokens * 0.35)),
      );
      const splitIdx = findSafeSplitIndex(messages, keepTokens, DEFAULT_KEEP_RECENT_MESSAGES);
      if (splitIdx <= 0) return null;

      const older = messages.slice(0, splitIdx);
      const recent = messages.slice(splitIdx);
      const excerpt = formatMessagesExcerpt(older);
      let summary = await this.generateSummaryWithModel(excerpt, signal);
      if (!summary) summary = buildExtractiveFallbackSummary(older);

      const summaryAnchor: AgentMessage = {
        role: "user",
        content: [
          {
            type: "text",
            text: `[已自动压缩早期对话摘要｜折叠 ${older.length} 条早期消息]\n${summary}`,
          },
        ],
        timestamp: Date.now(),
      } as AgentMessage;

      const nextMessages = [summaryAnchor, ...recent];
      this.lastReportedInputTokens = 0;
      this.lastReportedMsgCount = 0;
      const afterTokens = estimateConversationTokens(nextMessages, this.systemPrompt);
      const percent = Math.max(0, Math.min(100, Math.round((afterTokens / contextWindow) * 100)));

      bridge({
        type: "context_compacted",
        session: this.sessionId,
        blockId: "auto",
        foldedCount: older.length,
        beforeTokens,
        afterTokens,
        percent,
      });
      return { messages: nextMessages, beforeTokens, afterTokens };
    } catch {
      return null;
    } finally {
      this.isCompacting = false;
    }
  }

  /**
   * Registers the 5 official `acp-kernel` (billion-context) tools in-process when ACP mode is active:
   * `compress`, `decompress`, `search_context`, `acp_status`, `acp_retrieve`.
   */
  createAcpTools(
    getMessages: () => AgentMessage[],
    saveTranscript: () => void,
  ): AgentTool<any>[] {
    if (this.config.mode !== "acp") return [];

    return [
      {
        name: COMPRESS_TOOL_NAME,
        label: "压缩上下文区间",
        description:
          "Fold completed message ranges (mNNNNN..mMMMMM) or lower-tier blocks (bN..bM) into compact 3-tier LSM summaries. Summaries are the sole active record; originals remain recoverable via decompress/search_context.",
        parameters: COMPRESS_PARAMETERS as any,
        executionMode: "sequential" as const,
        execute: async (toolCallId: string, args: any) => {
          const parsed = parseCompressArgs(args, { callId: toolCallId });
          if (!parsed.diagnostics.ok || parsed.ranges.length === 0) {
            const reasons = parsed.diagnostics.invalidReasons?.join("; ") || parsed.diagnostics.kind;
            return {
              content: [{ type: "text", text: `compress 参数无效（${reasons}）。请提供 startRef、endRef 与 summary。` }],
              details: {},
              isError: true,
            } as any;
          }

          const messages = getMessages();
          const beforeTokens = this.computeUsage(messages).estimatedTokens;
          const coreMessages = this.toCoreMessages(messages);
          const applied = this.core.applyCompression({
            ranges: parsed.ranges,
            messages: coreMessages,
            state: this.acpState,
            config: this.acpConfig,
          });
          this.acpState = applied.state;

          if (applied.result.blocksCreated > 0) {
            this.lastReportedInputTokens = 0;
            this.lastReportedMsgCount = 0;
            const afterTokens = Math.max(0, beforeTokens - applied.result.tokensCompressed);
            this.lastEffectiveTokens = afterTokens;
            const contextWindow = Math.max(4_096, this.model.contextWindow || 128_000);
            const percent = Math.max(0, Math.min(100, Math.round((afterTokens / contextWindow) * 100)));
            const newest = this.acpState.blocks[this.acpState.blocks.length - 1];
            saveTranscript();
            this.emitUsage(messages);
            bridge({
              type: "context_compacted",
              session: this.sessionId,
              blockId: newest?.blockId ?? "b1",
              foldedCount: newest?.directMessageIds.length ?? parsed.ranges.length,
              beforeTokens,
              afterTokens,
              percent,
            });
          }

          const lines: string[] = [];
          if (applied.result.blocksCreated > 0) {
            lines.push(
              `Compressed ${applied.result.blocksCreated} block(s), reclaimed ~${applied.result.tokensCompressed} tokens. Active blocks: ${this.getActiveBlockCount()}.`,
            );
          }
          if (applied.result.notes?.length) {
            lines.push(`Notes: ${applied.result.notes.join("; ")}`);
          }
          if (applied.result.warnings.length) {
            lines.push(`Warnings: ${applied.result.warnings.join("; ")}`);
          }
          if (applied.result.errors.length) {
            lines.push(`Errors: ${applied.result.errors.join("; ")}`);
          }
          return {
            content: [{ type: "text", text: lines.join("\n") || "No ranges compressed." }],
            details: {},
            ...(applied.result.blocksCreated === 0 && applied.result.errors.length > 0 ? { isError: true } : {}),
          } as any;
        },
      },
      {
        name: DECOMPRESS_TOOL_NAME,
        label: "解压历史块",
        description:
          "Read the full original content of a compressed block (e.g. b1) inline without invalidating prompt cache.",
        parameters: {
          type: "object",
          properties: {
            blockId: {
              type: "string",
              description: "Block ID to restore (e.g. 'b1' or '#b1')",
            },
            full: {
              type: "boolean",
              description: "Set true to recurse through nested T2/T3 children to raw messages; default false (one tier up).",
            },
          },
          required: ["blockId"],
        } as any,
        executionMode: "sequential" as const,
        execute: async (_callId: string, args: any) => {
          const rawId = String(args?.blockId ?? args?.block_id ?? "").trim();
          const parsedId = parseBlockIdArg(rawId) ?? rawId.replace(/^#/, "");
          const block = this.core.decompress(parsedId, this.acpState);
          if (!block) {
            const ids = this.acpState.blocks.map((b) => b.blockId).join(", ") || "none";
            return {
              content: [{ type: "text", text: `Block '${rawId}' not found. Known blocks: ${ids}.` }],
              details: {},
              isError: true,
            } as any;
          }
          const coreMessages = this.toCoreMessages(getMessages());
          const collected = collectBlockContent(this.acpState, block, coreMessages, {
            full: Boolean(args?.full),
          });
          const marked = markBlockRestoredInline(this.acpState, block.blockId);
          this.acpState = marked.state;
          saveTranscript();
          const header = `[Decompressed #${block.blockId} (T${block.tier}, ${collected.count} items)]`;
          const body = collected.text || block.summary;
          return {
            content: [{ type: "text", text: `${header}\n${body.slice(0, 12_000)}` }],
            details: {},
          } as any;
        },
      },
      {
        name: SEARCH_CONTEXT_TOOL_NAME,
        label: "检索压缩历史",
        description:
          "Relevance-ranked search across all compressed block summaries AND folded historical messages. Returns owning blockIds to decompress.",
        parameters: {
          type: "object",
          properties: {
            query: { type: "string", description: "Search query keywords" },
            limit: { type: "integer", description: "Max results (default 6)" },
          },
          required: ["query"],
        } as any,
        executionMode: "sequential" as const,
        execute: async (_callId: string, args: any) => {
          const query = String(args?.query ?? "").trim();
          const limit = Math.max(1, Math.min(15, Number(args?.limit ?? 6)));
          const coreMessages = this.toCoreMessages(getMessages());
          const ownerMap = new Map<string, { blockId: string; tier: number }>();
          for (const b of this.acpState.blocks) {
            for (const id of b.effectiveMessageIds) {
              const ref = this.acpState.messageRefs.byRaw[id];
              if (ref && !ownerMap.has(ref)) ownerMap.set(ref, { blockId: b.blockId, tier: b.tier });
            }
          }
          const msgInputs = coreMessages
            .map((cm) => {
              const ref = this.acpState.messageRefs.byRaw[cm.id];
              if (!ref || !cm.text || cm.role === "system") return null;
              const owner = ownerMap.get(ref);
              return {
                ref,
                role: cm.role as "user" | "assistant" | "tool",
                text: cm.text,
                blockId: owner?.blockId,
                tier: owner?.tier,
              };
            })
            .filter((x): x is NonNullable<typeof x> => x !== null);

          const docs = [...blockDocs(this.acpState), ...messageDocs(msgInputs)];
          const hits = searchBlocks(docs, query, { limit });
          if (hits.length === 0) {
            // Fallback substring check over block summaries for short CJK phrases
            const direct = this.core.search(query, this.acpState).slice(0, limit);
            if (direct.length === 0) {
              return {
                content: [
                  {
                    type: "text",
                    text: `No matches for "${query}". Active blocks: ${activeBlocks(this.acpState).map((b) => b.blockId).join(", ") || "none"}.`,
                  },
                ],
                details: {},
              } as any;
            }
            const out = direct.map((b) => `#${b.blockId} (T${b.tier}): ${b.summary.slice(0, 320)}`).join("\n\n");
            return { content: [{ type: "text", text: out }], details: {} } as any;
          }

          const lines = hits.map((h) => {
            const owner = h.blockId && h.kind === "message" ? ` [block #${h.blockId}]` : "";
            const preview = (h.preview || h.title || "").replace(/\s+/g, " ").slice(0, 260);
            return `- ${h.ref}${owner} (score ${h.score.toFixed(2)}): ${preview}`;
          });
          return {
            content: [{ type: "text", text: `Search hits for "${query}":\n${lines.join("\n")}` }],
            details: {},
          } as any;
        },
      },
      {
        name: ACP_STATUS_TOOL_NAME,
        label: "查看上下文状态",
        description: "Report current context window usage, active LSM compression blocks, and compressible ranges.",
        parameters: {
          type: "object",
          properties: {},
        } as any,
        executionMode: "sequential" as const,
        execute: async () => {
          const coreMessages = this.toCoreMessages(getMessages());
          const report = buildStatusReport(this.acpState, coreMessages, estimateTextTokens, {
            meta: { host: "Zai", pack: "lean" },
          });
          return { content: [{ type: "text", text: report }], details: {} } as any;
        },
      },
      {
        name: RETRIEVE_TOOL_NAME,
        label: "读取外置原文",
        description: RETRIEVE_TOOL_DESCRIPTION,
        parameters: {
          type: "object",
          properties: {
            ref: { type: "string", description: "Message ref (e.g. m00004) from an acp-stored placeholder" },
          },
          required: ["ref"],
        } as any,
        executionMode: "sequential" as const,
        execute: async (_callId: string, args: any) => {
          const ref = String(args?.ref ?? "").trim().replace(/^#/, "");
          const res = this.core.retrieve(this.contentStore, ref);
          if (!res.ok) {
            return {
              content: [{ type: "text", text: res.toolResultText || `Stored content for ref '${ref}' not found.` }],
              details: {},
              isError: true,
            } as any;
          }
          const text = res.text || res.toolResultText || "";
          return {
            content: [{ type: "text", text: text.slice(0, 12_000) }],
            details: {},
          } as any;
        },
      },
    ];
  }

  private async generateSummaryWithModel(excerpt: string, signal?: AbortSignal): Promise<string> {
    try {
      const prompt = [
        "请按以下原则将这段早期对话压缩成高保真摘要，供后续对话无缝衔接：",
        "1. 保留用户的核心目标、明确偏好、关键结论、数据/时间/指令/路径与待办事项；",
        "2. 用紧凑条目记录事实与状态，不要编造问答，控制在 250~600 字内。",
        "",
        "--- 早期对话 ---",
        excerpt.slice(0, MAX_EXCERPT_CHARS_FOR_SUMMARY),
      ].join("\n");

      const res = await this.models.completeSimple(
        this.model,
        {
          systemPrompt: "你是严谨的对话上下文压缩器，只输出紧凑高保真的事实摘要。",
          messages: [
            {
              role: "user",
              content: [{ type: "text", text: prompt }],
              timestamp: Date.now(),
            },
          ],
        },
        {
          maxTokens: 1200,
          signal,
        },
      );
      return (res?.content ?? [])
        .filter((c: any) => c.type === "text")
        .map((c: any) => c.text)
        .join("")
        .trim();
    } catch {
      return "";
    }
  }
}
