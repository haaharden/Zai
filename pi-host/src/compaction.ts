import type { AgentMessage, AgentTool } from "@earendil-works/pi-agent-core";
import type { Api, Model, Models } from "@earendil-works/pi-ai";
import { bridge } from "./bridge.ts";

export type CompactionMode = "off" | "auto" | "acp";

export interface CompactionConfig {
  mode: CompactionMode;
  thresholdPercent: number; // 40..95, default 75
}

export interface ArchiveBlock {
  id: string;
  tier: number;
  summary: string;
  messageCount: number;
  approxTokens: number;
  originalExcerpt: string;
  createdAtMs: number;
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
const MAX_EXCERPT_CHARS_PER_BLOCK = 24_000;
const MIN_MESSAGES_TO_COMPACT = 6;
const DEFAULT_KEEP_RECENT_MESSAGES = 4;

export function normalizeCompactionConfig(raw?: Partial<CompactionConfig>): CompactionConfig {
  const mode: CompactionMode =
    raw?.mode === "off" || raw?.mode === "auto" || raw?.mode === "acp" ? raw.mode : "acp";
  const pct = Number(raw?.thresholdPercent ?? 75);
  const thresholdPercent = Number.isFinite(pct) ? Math.max(40, Math.min(95, Math.round(pct))) : 75;
  return { mode, thresholdPercent };
}

/**
 * Fast bilingual (CJK + Latin) token estimator.
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

export function estimateMessageTokens(msg: any): number {
  if (!msg) return 0;
  let tokens = 12; // role + framing overhead
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
        tokens += 24 + estimateTextTokens(String(part.name ?? "")) + estimateTextTokens(JSON.stringify(part.arguments ?? {}));
      } else if (part.type === "image") {
        tokens += 1200;
      }
    }
  }
  return tokens;
}

export function estimateConversationTokens(messages: readonly AgentMessage[], systemPrompt: string): number {
  let total = estimateTextTokens(systemPrompt) + 250; // tool specs baseline
  for (const m of messages) {
    total += estimateMessageTokens(m);
  }
  return total;
}

/**
 * Finds a safe index `splitIdx` such that messages `[0 .. splitIdx)` can be folded into a summary
 * without ever separating an assistant `toolCall` from its following `toolResult` messages.
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

  // Walk backward/forward to a clean boundary that doesn't start on a toolResult or assistant mid-tool
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

  // Must fold at least 2 messages to be worthwhile
  return candidate >= 2 ? candidate : 0;
}

function formatMessagesForArchive(messages: readonly AgentMessage[]): string {
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
  return joined.length > MAX_EXCERPT_CHARS_PER_BLOCK
    ? `${joined.slice(0, MAX_EXCERPT_CHARS_PER_BLOCK)}\n...[已截断]`
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
 * Strips persisted archive metadata out of restored transcript messages so the LLM never receives it.
 */
export function extractSavedArchiveBlocks(rawMessages?: any[]): {
  messages: AgentMessage[];
  blocks: ArchiveBlock[];
} {
  if (!Array.isArray(rawMessages) || rawMessages.length === 0) {
    return { messages: [], blocks: [] };
  }
  const blocks: ArchiveBlock[] = [];
  const clean: AgentMessage[] = [];
  for (const m of rawMessages) {
    if (m && m.role === ARCHIVE_META_ROLE && Array.isArray(m.blocks)) {
      for (const b of m.blocks) {
        if (b && typeof b.id === "string" && typeof b.summary === "string") {
          blocks.push({
            id: b.id,
            tier: Number(b.tier) || 1,
            summary: b.summary,
            messageCount: Number(b.messageCount) || 0,
            approxTokens: Number(b.approxTokens) || 0,
            originalExcerpt: String(b.originalExcerpt ?? ""),
            createdAtMs: Number(b.createdAtMs) || Date.now(),
          });
        }
      }
      continue;
    }
    if (m && m.role !== "system") {
      clean.push(m);
    }
  }
  return { messages: clean, blocks };
}

/**
 * Per-session Context Compaction & Active Context Pruning (ACP, billion-context inspired) engine.
 */
export class SessionCompactor {
  private blocks: ArchiveBlock[] = [];
  private seq = 0;
  private lastReportedInputTokens = 0;
  private lastReportedMsgCount = 0;
  private isCompacting = false;

  constructor(
    private readonly sessionId: string,
    private readonly models: Models,
    private readonly model: Model<Api>,
    private readonly systemPrompt: string,
    public readonly config: CompactionConfig,
    initialBlocks: ArchiveBlock[] = [],
  ) {
    this.blocks = [...initialBlocks];
    this.seq = initialBlocks.reduce((max, b) => {
      const num = Number(b.id.replace(/^b/i, ""));
      return Number.isFinite(num) ? Math.max(max, num) : max;
    }, 0);
  }

  getArchiveBlocks(): ArchiveBlock[] {
    return this.blocks;
  }

  recordActualInputTokens(inputTokens: number, messageCount: number) {
    if (inputTokens > 0) {
      this.lastReportedInputTokens = inputTokens;
      this.lastReportedMsgCount = messageCount;
    }
  }

  computeUsage(messages: readonly AgentMessage[]): ContextUsageSnapshot {
    const estimated = estimateConversationTokens(messages, this.systemPrompt);
    const effective =
      this.lastReportedInputTokens > 0 && messages.length >= this.lastReportedMsgCount
        ? Math.max(estimated, this.lastReportedInputTokens)
        : estimated;
    const contextWindow = Math.max(4_096, this.model.contextWindow || 128_000);
    const percent = Math.max(0, Math.min(100, Math.round((effective / contextWindow) * 100)));
    return {
      estimatedTokens: effective,
      contextWindow,
      percent,
      archivedBlocks: this.blocks.length,
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
  async maybeAutoCompact(
    messages: AgentMessage[],
    onReplaced: (nextMessages: AgentMessage[]) => void,
    signal?: AbortSignal,
  ): Promise<AgentMessage[]> {
    const usage = this.emitUsage(messages);
    if (this.config.mode === "off") return messages;
    if (this.isCompacting || messages.length < MIN_MESSAGES_TO_COMPACT) return messages;
    if (usage.percent < this.config.thresholdPercent) return messages;

    const compacted = await this.compactMessages(messages, undefined, undefined, signal);
    if (!compacted) return messages;
    onReplaced(compacted.messages);
    this.emitUsage(compacted.messages);
    return compacted.messages;
  }

  /**
   * Executes compaction (triggered either automatically at threshold, manually by user, or proactively by ACP tool).
   */
  async compactMessages(
    messages: AgentMessage[],
    customSummary?: string,
    keepRecentMessages?: number,
    signal?: AbortSignal,
  ): Promise<{
    messages: AgentMessage[];
    block: ArchiveBlock;
    beforeTokens: number;
    afterTokens: number;
  } | null> {
    if (this.isCompacting) return null;
    this.isCompacting = true;
    try {
      const beforeTokens = this.computeUsage(messages).estimatedTokens;
      const contextWindow = Math.max(4_096, this.model.contextWindow || 128_000);
      const keepTokens = Math.min(
        Math.round(contextWindow * 0.25),
        Math.max(4_000, Math.round(beforeTokens * 0.35)),
      );
      const minKeep = Math.max(2, Math.min(12, keepRecentMessages ?? DEFAULT_KEEP_RECENT_MESSAGES));
      const splitIdx = findSafeSplitIndex(messages, keepTokens, minKeep);
      if (splitIdx <= 0) return null;

      const older = messages.slice(0, splitIdx);
      const recent = messages.slice(splitIdx);
      const foldedTokens = older.reduce((sum, m) => sum + estimateMessageTokens(m), 0);
      const excerpt = formatMessagesForArchive(older);

      let summary = customSummary?.trim() || "";
      if (!summary) {
        summary = await this.generateSummaryWithModel(excerpt, signal);
      }
      if (!summary) {
        summary = buildExtractiveFallbackSummary(older);
      }

      const block: ArchiveBlock = {
        id: `b${++this.seq}`,
        tier: 1,
        summary,
        messageCount: older.length,
        approxTokens: foldedTokens,
        originalExcerpt: excerpt,
        createdAtMs: Date.now(),
      };
      this.blocks.push(block);
      // Cap total stored archive blocks in transcript memory cleanly (keep last 24)
      if (this.blocks.length > 24) {
        this.blocks = this.blocks.slice(-24);
      }

      const acpHint =
        this.config.mode === "acp"
          ? "（如需回忆原文细节，可调用 context_search 或 context_expand）"
          : "";
      const summaryAnchor: AgentMessage = {
        role: "user",
        content: [
          {
            type: "text",
            text: `[已压缩早期对话摘要 #${block.id}｜折叠 ${older.length} 条消息约 ${Math.round(foldedTokens / 100) / 10}K tokens]${acpHint}\n${summary}`,
          },
        ],
        timestamp: Date.now(),
      } as AgentMessage;

      const nextMessages: AgentMessage[] = [summaryAnchor, ...recent];
      this.lastReportedInputTokens = 0;
      this.lastReportedMsgCount = 0;

      const afterTokens = estimateConversationTokens(nextMessages, this.systemPrompt);
      const afterPercent = Math.max(0, Math.min(100, Math.round((afterTokens / contextWindow) * 100)));

      bridge({
        type: "context_compacted",
        session: this.sessionId,
        blockId: block.id,
        foldedCount: older.length,
        beforeTokens,
        afterTokens,
        percent: afterPercent,
      });

      return { messages: nextMessages, block, beforeTokens, afterTokens };
    } catch {
      return null;
    } finally {
      this.isCompacting = false;
    }
  }

  /**
   * Builds native in-process ACP tools (billion-context style) for model-driven context management.
   */
  createAcpTools(
    getMessages: () => AgentMessage[],
    setMessages: (next: AgentMessage[]) => void,
    saveTranscript: () => void,
  ): AgentTool<any>[] {
    if (this.config.mode !== "acp") return [];

    return [
      {
        name: "context_compress",
        label: "主动折叠早期上下文",
        description:
          "将当前会话中较早的历史消息折叠为一个可检索、可解压的压缩块（保留近期工作消息），节省上下文窗口。长对话或任务阶段完成时可主动调用。",
        parameters: {
          type: "object",
          properties: {
            summary: {
              type: "string",
              description: "对早期对话的高保真结构化摘要（保留关键事实、用户偏好、结论、文件名/参数与待办事项）",
            },
            keep_recent_turns: {
              type: "integer",
              description: "保留最近几条消息不折叠，默认 4（范围 2~10）",
            },
          },
          required: ["summary"],
        } as any,
        executionMode: "sequential" as const,
        execute: async (_callId: string, args: any, signal?: AbortSignal) => {
          const summary = String(args?.summary ?? "").trim();
          const keep = Number(args?.keep_recent_turns ?? 4);
          const current = getMessages();
          const res = await this.compactMessages(current, summary || undefined, keep, signal);
          if (!res) {
            const usage = this.computeUsage(current);
            return {
              content: [
                {
                  type: "text",
                  text: `当前消息数较少（共 ${current.length} 条，约 ${usage.percent}%），暂无需折叠。`,
                },
              ],
              details: {},
            } as any;
          }
          setMessages(res.messages);
          saveTranscript();
          const afterUsage = this.emitUsage(res.messages);
          return {
            content: [
              {
                type: "text",
                text: `已将 ${res.block.messageCount} 条早期消息折叠为归档块 #${res.block.id}，上下文由约 ${res.beforeTokens} tokens 降至 ${res.afterTokens} tokens（当前占用 ${afterUsage.percent}%）。`,
              },
            ],
            details: {},
          } as any;
        },
      },
      {
        name: "context_search",
        label: "检索已压缩历史",
        description: "在已折叠归档的早期对话块中按关键词检索细节，无需把整段历史重新解压进上下文。",
        parameters: {
          type: "object",
          properties: {
            query: {
              type: "string",
              description: "检索关键词（支持空格分隔多个词）",
            },
          },
          required: ["query"],
        } as any,
        executionMode: "sequential" as const,
        execute: async (_callId: string, args: any) => {
          const query = String(args?.query ?? "").trim();
          if (this.blocks.length === 0) {
            return {
              content: [{ type: "text", text: "当前会话尚无已压缩归档块。" }],
              details: {},
            } as any;
          }
          const terms = query
            .toLowerCase()
            .split(/\s+/)
            .filter((t) => t.length > 0);
          const hits: string[] = [];
          for (const b of this.blocks) {
            const haystack = `${b.summary}\n${b.originalExcerpt}`.toLowerCase();
            if (terms.length === 0 || terms.some((t) => haystack.includes(t))) {
              const snippetLines = b.originalExcerpt
                .split("\n")
                .filter((line) => terms.length === 0 || terms.some((t) => line.toLowerCase().includes(t)))
                .slice(0, 4)
                .map((l) => `  ${l.slice(0, 220)}`);
              hits.push(
                `#${b.id} (${b.messageCount}条消息)\n摘要：${b.summary.slice(0, 260)}${
                  snippetLines.length ? `\n命中片段：\n${snippetLines.join("\n")}` : ""
                }`,
              );
            }
          }
          const text =
            hits.length > 0
              ? `在 ${this.blocks.length} 个归档块中命中 ${hits.length} 条：\n\n${hits.join("\n\n")}`
              : `未在 ${this.blocks.length} 个归档块中找到与「${query}」相关的内容。可用块：${this.blocks.map((b) => b.id).join(", ")}`;
          return { content: [{ type: "text", text }], details: {} } as any;
        },
      },
      {
        name: "context_expand",
        label: "解压归档上下文",
        description: "按块编号（如 b1）还原某段已压缩历史块的完整原文片段。",
        parameters: {
          type: "object",
          properties: {
            block_id: {
              type: "string",
              description: "归档块编号，例如 b1",
            },
          },
          required: ["block_id"],
        } as any,
        executionMode: "sequential" as const,
        execute: async (_callId: string, args: any) => {
          const id = String(args?.block_id ?? "").trim().replace(/^#/, "");
          const hit = this.blocks.find((b) => b.id.toLowerCase() === id.toLowerCase());
          if (!hit) {
            return {
              content: [
                {
                  type: "text",
                  text: `未找到归档块 ${id}。当前可用块：${this.blocks.map((b) => b.id).join(", ") || "无"}`,
                },
              ],
              details: {},
              isError: true,
            } as any;
          }
          return {
            content: [
              {
                type: "text",
                text: `[归档块 #${hit.id} 原文展开｜共 ${hit.messageCount} 条消息]\n${hit.originalExcerpt.slice(0, 8000)}`,
              },
            ],
            details: {},
          } as any;
        },
      },
    ];
  }

  private async generateSummaryWithModel(excerpt: string, signal?: AbortSignal): Promise<string> {
    try {
      const prompt = [
        "请把下面这段早期对话压缩成一份紧凑、高保真的中文工作摘要，供后续对话无缝衔接。",
        "要求：",
        "1. 保留用户的核心目标、明确偏好、关键结论、数据/时间/指令/代码标识以及尚未完成的事项；",
        "2. 删去寒暄、重复尝试和冗余过程，控制在 300~600 字内，使用简洁条目。",
        "",
        "--- 早期对话 ---",
        excerpt.slice(0, 18_000),
      ].join("\n");

      const res = await this.models.completeSimple(
        this.model,
        {
          systemPrompt: "你是严谨的对话上下文压缩器，只输出简洁、高保真的事实摘要，不要添加多余开场白。",
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
      const text = (res?.content ?? [])
        .filter((c: any) => c.type === "text")
        .map((c: any) => c.text)
        .join("")
        .trim();
      return text;
    } catch {
      return "";
    }
  }
}
