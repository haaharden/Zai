import type { AgentMessage, AgentTool } from "@earendil-works/pi-agent-core";
import { bridgeCall } from "./bridge.ts";

export const SCREEN_NOW = "\n\nScreen now:\n";

export type ToolSpec = {
  name: string;
  label: string;
  description: string;
  parameters: Record<string, unknown>;
};

type ToolAnswer = {
  text?: string;
  isError?: boolean;
  image?: { data: string; mimeType: string };
};

export function createPhoneTools(session: string, specs: ToolSpec[]): AgentTool<any>[] {
  return specs.map((spec) => ({
    name: spec.name,
    label: spec.label,
    description: spec.description,
    parameters: spec.parameters as any,
    executionMode: "sequential" as const,
    execute: async (toolCallId: string, args: any, signal?: AbortSignal) => {
      signal?.throwIfAborted();
      const onAbort = () => {
        void bridgeCall("tool_cancel", { session, call: toolCallId }).catch(() => {});
      };
      signal?.addEventListener("abort", onAbort, { once: true });
      try {
        const res: ToolAnswer = await bridgeCall("tool", {
          session,
          call: toolCallId,
          name: spec.name,
          args,
        });
        const content: any[] = [{ type: "text", text: res?.text ?? String(res) }];
        if (res?.image?.data) {
          content.push({
            type: "image",
            data: res.image.data,
            mimeType: res.image.mimeType || "image/jpeg",
          });
        }
        return {
          content,
          details: {},
          ...(res?.isError ? { isError: true } : {}),
        } as any;
      } finally {
        signal?.removeEventListener("abort", onAbort);
      }
    },
  }));
}

const SCREEN_TOOL_NAMES = new Set([
  "read_screen",
  "screenshot",
  "scroll_find_tap",
  "run_actions",
  "open_system_setting",
  "open_uri",
]);

export function pruneScreens(
  messages: AgentMessage[],
  keepTextScreens: number = 2,
  keepImages: number = 1,
  keepUserImageTurns: number = 3,
): AgentMessage[] {
  const textOf = (m: any): string =>
    Array.isArray(m.content) ? (m.content.find((c: any) => c.type === "text")?.text ?? "") : "";

  const screenIndexes = messages
    .map((m: any, idx) =>
      m.role === "toolResult" &&
      (SCREEN_TOOL_NAMES.has(String(m.toolName ?? "")) || textOf(m).includes(SCREEN_NOW))
        ? idx
        : -1,
    )
    .filter((i) => i >= 0);

  const toolImageIndexes = messages
    .map((m: any, idx) =>
      m.role === "toolResult" &&
      Array.isArray(m.content) &&
      m.content.some((c: any) => c?.type === "image")
        ? idx
        : -1,
    )
    .filter((i) => i >= 0);

  const userImageIndexes = messages
    .map((m: any, idx) =>
      m.role === "user" &&
      Array.isArray(m.content) &&
      m.content.some((c: any) => c?.type === "image")
        ? idx
        : -1,
    )
    .filter((i) => i >= 0);

  const dropTextSet = new Set(screenIndexes.slice(0, Math.max(0, screenIndexes.length - keepTextScreens)));
  const dropToolImageSet = new Set(toolImageIndexes.slice(0, Math.max(0, toolImageIndexes.length - keepImages)));
  const dropUserImageSet = new Set(
    userImageIndexes.slice(0, Math.max(0, userImageIndexes.length - keepUserImageTurns)),
  );
  if (dropTextSet.size === 0 && dropToolImageSet.size === 0 && dropUserImageSet.size === 0) {
    return messages;
  }

  return messages.map((m: any, idx) => {
    if (dropTextSet.has(idx)) {
      const text = textOf(m);
      const trimmed = text.includes(SCREEN_NOW)
        ? `${text.slice(0, text.indexOf(SCREEN_NOW))}\n[older screen snapshot omitted]`
        : `[earlier ${m.toolName} output omitted; screen has changed]`;
      return { ...m, content: [{ type: "text", text: trimmed }] };
    }
    if (dropToolImageSet.has(idx) && Array.isArray(m.content)) {
      return {
        ...m,
        content: m.content.map((c: any) =>
          c?.type === "image" ? { type: "text", text: "[earlier screenshot omitted]" } : c,
        ),
      };
    }
    if (dropUserImageSet.has(idx) && Array.isArray(m.content)) {
      return {
        ...m,
        content: m.content.map((c: any) =>
          c?.type === "image" ? { type: "text", text: "[earlier attached image omitted]" } : c,
        ),
      };
    }
    return m;
  });
}

export async function pruneOldScreens(messages: AgentMessage[]): Promise<AgentMessage[]> {
  return pruneScreens(messages, 2, 1, 3);
}

export function savableTranscript(messages: AgentMessage[]) {
  return pruneScreens(messages, 2, 0, 0).flatMap((m: any) => {
    if (m.role === "system") return [];
    if (!Array.isArray(m.content) || !m.content.some((c: any) => c.type === "image")) return m;
    const placeholder = m.role === "user" ? "[attached image]" : "[screenshot omitted]";
    return {
      ...m,
      content: m.content.map((c: any) => (c.type === "image" ? { type: "text", text: placeholder } : c)),
    };
  });
}
