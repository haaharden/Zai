import type { CredentialStore, Models } from "@earendil-works/pi-ai";
import { bridge } from "./bridge.ts";
import { PRIMARY_OAUTH_PROVIDER_ID, type CustomProviderConfig } from "./providers.ts";

export interface QuotaWindowSnapshot {
  usedPercent: number;
  remainingPercent: number;
  windowMinutes: number;
  resetAtMs: number;
}

export interface ResetCreditItem {
  creditId: string;
  status: string;
  resetType?: string;
  expiresAtMs?: number;
}

export interface ResetCreditsInfo {
  availableCount: number;
  applicableCount: number;
  credits: ResetCreditItem[];
}

export interface ProviderQuotaSnapshot {
  providerId: string;
  kind: "subscription" | "balance";
  summaryText?: string;
  detailText?: string;
  planType?: string;
  email?: string;
  primary?: QuotaWindowSnapshot;
  secondary?: QuotaWindowSnapshot;
  limitReached: boolean;
  creditsUnlimited?: boolean;
  creditsBalance?: string;
  resetCredits?: ResetCreditsInfo;
  updatedAtMs: number;
  error?: string;
}

const CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
const CODEX_RESET_CREDITS_URL = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits";
const CODEX_RESET_CONSUME_URL = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume";
const OPENROUTER_CREDITS_URL = "https://openrouter.ai/api/v1/credits";
const OPENROUTER_KEY_URL = "https://openrouter.ai/api/v1/key";
const DEEPSEEK_BALANCE_URL = "https://api.deepseek.com/user/balance";

const SOFT_TTL_MS = 45_000;
const JWT_AUTH_CLAIM = "https://api.openai.com/auth";

function clampPercent(raw: unknown): number | undefined {
  const n = typeof raw === "number" ? raw : typeof raw === "string" ? Number(raw) : NaN;
  if (!Number.isFinite(n)) return undefined;
  return Math.max(0, Math.min(100, Math.round(n)));
}

function parseTimestampMs(raw: unknown): number {
  const n = typeof raw === "number" ? raw : typeof raw === "string" ? Number(raw) : NaN;
  if (!Number.isFinite(n) || n <= 0) return 0;
  return n > 1e12 ? Math.round(n) : Math.round(n * 1000);
}

function parseFiniteNumber(raw: unknown): number | undefined {
  if (raw === null || raw === undefined || raw === "") return undefined;
  const n = typeof raw === "number" ? raw : Number(raw);
  return Number.isFinite(n) ? n : undefined;
}

function formatMoney(amount: number, symbol: string = "$"): string {
  const fixed = Math.abs(amount) >= 100 ? amount.toFixed(2) : amount.toFixed(2);
  return `${symbol}${fixed}`;
}

function formatWindowShort(win: QuotaWindowSnapshot): string {
  const label =
    win.windowMinutes <= 0
      ? "周期"
      : win.windowMinutes < 60
        ? `${win.windowMinutes}m`
        : win.windowMinutes % (24 * 60) === 0
          ? win.windowMinutes === 7 * 24 * 60
            ? "周"
            : `${win.windowMinutes / (24 * 60)}天`
          : win.windowMinutes % 60 === 0
            ? `${win.windowMinutes / 60}h`
            : `${win.windowMinutes}m`;
  return `${label} ${win.remainingPercent}%`;
}

function buildSubscriptionSummary(primary?: QuotaWindowSnapshot, secondary?: QuotaWindowSnapshot): string | undefined {
  const parts: string[] = [];
  if (primary) parts.push(formatWindowShort(primary));
  if (secondary) parts.push(formatWindowShort(secondary));
  return parts.length > 0 ? parts.join(" · ") : undefined;
}

function generateRequestUuid(): string {
  try {
    if (typeof crypto !== "undefined" && typeof (crypto as any).randomUUID === "function") {
      return (crypto as any).randomUUID();
    }
  } catch {
    // fallback below
  }
  return `zai-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

function parseWindowObject(win: any, nowMs: number): QuotaWindowSnapshot | undefined {
  if (!win || typeof win !== "object") return undefined;
  const used = clampPercent(win.used_percent ?? win.usedPercent);
  if (used === undefined) return undefined;
  const secLen = Number(win.limit_window_seconds ?? win.limitWindowSeconds ?? 0);
  const windowMinutes = secLen > 0 ? Math.round(secLen / 60) : 0;
  let resetAtMs = parseTimestampMs(win.reset_at ?? win.resetAt);
  if (resetAtMs <= 0) {
    const afterSec = Number(win.reset_after_seconds ?? win.resetAfterSeconds ?? 0);
    if (Number.isFinite(afterSec) && afterSec > 0) {
      resetAtMs = Math.round(nowMs + afterSec * 1000);
    }
  }
  return {
    usedPercent: used,
    remainingPercent: Math.max(0, 100 - used),
    windowMinutes,
    resetAtMs,
  };
}

function decodeJwtAccountId(accessToken: string): string | undefined {
  try {
    const parts = accessToken.split(".");
    if (parts.length < 2) return undefined;
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
    const jsonStr = atob(padded);
    const payload = JSON.parse(jsonStr);
    const id = payload?.[JWT_AUTH_CLAIM]?.chatgpt_account_id;
    return typeof id === "string" && id.length > 0 ? id : undefined;
  } catch {
    return undefined;
  }
}

export function parseCodexResetCreditsJson(json: any): ResetCreditsInfo {
  const container = json?.rate_limit_reset_credits ?? json?.rateLimitResetCredits ?? json;
  const rawList: any[] = Array.isArray(container?.credits)
    ? container.credits
    : Array.isArray(container)
      ? container
      : [];

  const credits: ResetCreditItem[] = rawList
    .map((item: any): ResetCreditItem | null => {
      if (!item || typeof item !== "object") return null;
      const creditId = String(item.credit_id ?? item.creditId ?? item.id ?? "").trim();
      if (!creditId) return null;
      const status = String(item.status ?? "available").trim().toLowerCase();
      const resetType =
        typeof (item.reset_type ?? item.resetType ?? item.limit_name) === "string"
          ? String(item.reset_type ?? item.resetType ?? item.limit_name).trim()
          : undefined;
      const expiresAtMs = parseTimestampMs(item.expires_at ?? item.expiresAt ?? 0);
      return {
        creditId,
        status,
        resetType,
        expiresAtMs: expiresAtMs > 0 ? expiresAtMs : undefined,
      };
    })
    .filter((x): x is ResetCreditItem => x !== null);

  const availableList = credits.filter((c) => c.status === "available" || c.status === "active" || c.status === "unused");
  const rawAvail = parseFiniteNumber(container?.available_count ?? container?.availableCount);
  const rawApplicable = parseFiniteNumber(
    container?.applicable_available_count ?? container?.applicableAvailableCount,
  );
  const availableCount = rawAvail !== undefined ? Math.max(0, Math.round(rawAvail)) : availableList.length;
  const applicableCount = rawApplicable !== undefined ? Math.max(0, Math.round(rawApplicable)) : availableCount;

  return {
    availableCount,
    applicableCount,
    credits: availableList.length > 0 ? availableList : credits,
  };
}

export function parseCodexUsageJson(
  json: any,
  nowMs: number = Date.now(),
  resetCredits?: ResetCreditsInfo,
): ProviderQuotaSnapshot | null {
  if (!json || typeof json !== "object") return null;
  const rateLimit = json.rate_limit ?? json.rateLimit;
  let primary = parseWindowObject(rateLimit?.primary_window ?? rateLimit?.primaryWindow, nowMs);
  let secondary = parseWindowObject(rateLimit?.secondary_window ?? rateLimit?.secondaryWindow, nowMs);

  const extras = Array.isArray(json.additional_rate_limits) ? json.additional_rate_limits : [];
  if (primary && !secondary && extras.length > 0) {
    for (const item of extras) {
      const candidate = parseWindowObject(item?.primary_window ?? item?.rate_limit?.primary_window, nowMs);
      if (candidate && candidate.windowMinutes !== primary.windowMinutes) {
        if (candidate.windowMinutes > 0 && candidate.windowMinutes < primary.windowMinutes) {
          secondary = primary;
          primary = candidate;
        } else {
          secondary = candidate;
        }
        break;
      }
    }
  }

  if (
    primary &&
    secondary &&
    primary.windowMinutes > 0 &&
    secondary.windowMinutes > 0 &&
    primary.windowMinutes > secondary.windowMinutes
  ) {
    const tmp = primary;
    primary = secondary;
    secondary = tmp;
  }

  const credits = json.credits;
  let creditsBalance: string | undefined;
  if (credits && credits.balance !== null && credits.balance !== undefined && String(credits.balance).trim() !== "") {
    creditsBalance = String(credits.balance).trim();
  }

  const planType = typeof json.plan_type === "string" && json.plan_type.trim() ? json.plan_type.trim() : undefined;
  const email = typeof json.email === "string" && json.email.trim() ? json.email.trim() : undefined;
  const limitReached = Boolean(rateLimit?.limit_reached || rateLimit?.allowed === false);

  if (!primary && !secondary && !planType && !creditsBalance) return null;

  return {
    providerId: PRIMARY_OAUTH_PROVIDER_ID,
    kind: "subscription",
    summaryText: buildSubscriptionSummary(primary, secondary),
    planType,
    email,
    primary,
    secondary,
    limitReached,
    creditsUnlimited: typeof credits?.unlimited === "boolean" ? credits.unlimited : undefined,
    creditsBalance,
    resetCredits,
    updatedAtMs: nowMs,
  };
}

export function parseCodexResponseHeaders(
  headers: Record<string, string>,
  existing?: ProviderQuotaSnapshot,
  nowMs: number = Date.now(),
): ProviderQuotaSnapshot | null {
  const lower: Record<string, string> = {};
  for (const [k, v] of Object.entries(headers ?? {})) {
    lower[k.toLowerCase()] = String(v);
  }

  const readWindow = (prefix: "primary" | "secondary"): QuotaWindowSnapshot | undefined => {
    const used = clampPercent(lower[`x-codex-${prefix}-used-percent`]);
    if (used === undefined) return undefined;
    const mins = Number(lower[`x-codex-${prefix}-window-minutes`] ?? 0);
    let resetAtMs = parseTimestampMs(lower[`x-codex-${prefix}-reset-at`]);
    if (resetAtMs <= 0) {
      const afterSec = Number(lower[`x-codex-${prefix}-reset-after-seconds`] ?? 0);
      if (Number.isFinite(afterSec) && afterSec > 0) {
        resetAtMs = Math.round(nowMs + afterSec * 1000);
      }
    }
    return {
      usedPercent: used,
      remainingPercent: Math.max(0, 100 - used),
      windowMinutes: Number.isFinite(mins) && mins > 0 ? Math.round(mins) : 0,
      resetAtMs,
    };
  };

  let primary = readWindow("primary");
  let secondary = readWindow("secondary");
  const planType = lower["x-codex-plan-type"]?.trim() || existing?.planType;
  const rawBal = lower["x-codex-credits-balance"]?.trim();
  const creditsUnlimited =
    lower["x-codex-credits-unlimited"] === "true"
      ? true
      : lower["x-codex-credits-unlimited"] === "false"
        ? false
        : existing?.creditsUnlimited;

  if (!primary && !secondary) return null;

  if (
    primary &&
    secondary &&
    primary.windowMinutes > 0 &&
    secondary.windowMinutes > 0 &&
    primary.windowMinutes > secondary.windowMinutes
  ) {
    const tmp = primary;
    primary = secondary;
    secondary = tmp;
  }

  const nextPrimary = primary ?? existing?.primary;
  const nextSecondary = secondary ?? existing?.secondary;
  return {
    providerId: PRIMARY_OAUTH_PROVIDER_ID,
    kind: "subscription",
    summaryText: buildSubscriptionSummary(nextPrimary, nextSecondary),
    planType,
    email: existing?.email,
    primary: nextPrimary,
    secondary: nextSecondary,
    limitReached: nextPrimary?.remainingPercent === 0 || nextSecondary?.remainingPercent === 0,
    creditsUnlimited,
    creditsBalance: rawBal || existing?.creditsBalance,
    resetCredits: existing?.resetCredits,
    updatedAtMs: nowMs,
  };
}

export function parseDeepSeekBalanceJson(
  providerId: string,
  json: any,
  nowMs: number = Date.now(),
): ProviderQuotaSnapshot | null {
  if (!json || typeof json !== "object") return null;
  const infos = Array.isArray(json.balance_infos) ? json.balance_infos : [];
  const isAvailable = json.is_available !== false;
  if (infos.length === 0) {
    return {
      providerId,
      kind: "balance",
      summaryText: isAvailable ? "余额可用" : "余额不足",
      limitReached: !isAvailable,
      updatedAtMs: nowMs,
    };
  }

  const summaryParts: string[] = [];
  const detailParts: string[] = [];
  for (const item of infos) {
    const currency = String(item?.currency ?? "CNY").toUpperCase();
    const sym = currency === "USD" ? "$" : "¥";
    const total = parseFiniteNumber(item?.total_balance);
    const granted = parseFiniteNumber(item?.granted_balance);
    const toppedUp = parseFiniteNumber(item?.topped_up_balance);
    if (total !== undefined) {
      summaryParts.push(formatMoney(total, sym));
    }
    const sub: string[] = [];
    if (toppedUp !== undefined && toppedUp > 0) sub.push(`充值 ${formatMoney(toppedUp, sym)}`);
    if (granted !== undefined && granted > 0) sub.push(`赠金 ${formatMoney(granted, sym)}`);
    if (sub.length > 0) detailParts.push(sub.join(" · "));
  }

  const primaryBalance = summaryParts.join(" / ");
  return {
    providerId,
    kind: "balance",
    summaryText: primaryBalance ? `余 ${primaryBalance}` : isAvailable ? "余额可用" : "余额不足",
    detailText: detailParts.length > 0 ? detailParts.join(" | ") : undefined,
    creditsBalance: primaryBalance || undefined,
    limitReached: !isAvailable,
    updatedAtMs: nowMs,
  };
}

export function parseOpenRouterQuotaJson(
  providerId: string,
  creditsJson: any,
  keyJson: any,
  nowMs: number = Date.now(),
): ProviderQuotaSnapshot | null {
  const cData = creditsJson?.data ?? creditsJson;
  const totalCredits = parseFiniteNumber(cData?.total_credits);
  const totalUsage = parseFiniteNumber(cData?.total_usage);

  const kData = keyJson?.data ?? keyJson;
  const keyLimit = parseFiniteNumber(kData?.limit);
  const keyLimitRem = parseFiniteNumber(kData?.limit_remaining);
  const keyUsage = parseFiniteNumber(kData?.usage);
  const usageDaily = parseFiniteNumber(kData?.usage_daily);
  const usageMonthly = parseFiniteNumber(kData?.usage_monthly);
  const isFreeTier = kData?.is_free_tier === true;
  const freeDailyRem = parseFiniteNumber(kData?.free_model_daily_requests?.remaining);
  const freeDailyLim = parseFiniteNumber(kData?.free_model_daily_requests?.limit);

  if (totalCredits !== undefined && totalUsage !== undefined) {
    const remaining = Math.max(0, totalCredits - totalUsage);
    const detailParts = [`已用 ${formatMoney(totalUsage)} / 总额 ${formatMoney(totalCredits)}`];
    if (keyLimitRem !== undefined && keyLimit !== undefined) {
      detailParts.push(`Key 剩 ${formatMoney(keyLimitRem)}`);
    }
    return {
      providerId,
      kind: "balance",
      summaryText: `余 ${formatMoney(remaining)}`,
      detailText: detailParts.join(" · "),
      creditsBalance: formatMoney(remaining),
      limitReached: remaining <= 0 && !isFreeTier,
      updatedAtMs: nowMs,
    };
  }

  if (keyLimitRem !== undefined) {
    const detailParts: string[] = [];
    if (keyUsage !== undefined && keyLimit !== undefined) {
      detailParts.push(`已用 ${formatMoney(keyUsage)} / 上限 ${formatMoney(keyLimit)}`);
    }
    if (typeof kData?.limit_reset === "string" && kData.limit_reset) {
      detailParts.push(`${kData.limit_reset} 重置`);
    }
    return {
      providerId,
      kind: "balance",
      summaryText: `余 ${formatMoney(Math.max(0, keyLimitRem))}`,
      detailText: detailParts.length > 0 ? detailParts.join(" · ") : undefined,
      creditsBalance: formatMoney(Math.max(0, keyLimitRem)),
      limitReached: keyLimitRem <= 0,
      updatedAtMs: nowMs,
    };
  }

  if (keyUsage !== undefined || freeDailyRem !== undefined) {
    const summaryText =
      isFreeTier && freeDailyRem !== undefined && freeDailyLim !== undefined
        ? `免费剩 ${Math.round(freeDailyRem)}/${Math.round(freeDailyLim)}次`
        : keyUsage !== undefined
          ? `已用 ${formatMoney(keyUsage)}`
          : "Key 有效";
    const detailParts: string[] = [];
    if (usageDaily !== undefined) detailParts.push(`今日 ${formatMoney(usageDaily)}`);
    if (usageMonthly !== undefined) detailParts.push(`本月 ${formatMoney(usageMonthly)}`);
    if (keyLimit === undefined && !isFreeTier) detailParts.push("Key 未设额度上限");
    return {
      providerId,
      kind: "balance",
      summaryText,
      detailText: detailParts.length > 0 ? detailParts.join(" · ") : undefined,
      creditsBalance: keyUsage !== undefined ? `已用 ${formatMoney(keyUsage)}` : undefined,
      limitReached: kData?.disabled === true,
      updatedAtMs: nowMs,
    };
  }

  return null;
}

type ProviderQuotaAdapter = "codex" | "openrouter" | "deepseek" | "none";

export class QuotaTracker {
  private readonly cache = new Map<string, ProviderQuotaSnapshot>();
  private readonly inflight = new Map<string, Promise<ProviderQuotaSnapshot | null>>();
  private readonly customMeta = new Map<string, CustomProviderConfig>();

  constructor(
    private readonly models: Models,
    private readonly credentials: CredentialStore,
  ) {}

  setCustomProviders(list: CustomProviderConfig[]) {
    const keep = new Set(list.map((c) => c.id));
    for (const id of [...this.customMeta.keys()]) {
      if (!keep.has(id)) {
        this.customMeta.delete(id);
        this.clear(id);
      }
    }
    for (const cfg of list) {
      this.customMeta.set(cfg.id, cfg);
    }
  }

  private resolveAdapter(providerId: string): ProviderQuotaAdapter {
    if (providerId === PRIMARY_OAUTH_PROVIDER_ID) return "codex";
    const cfg = this.customMeta.get(providerId);
    const url = cfg?.baseUrl?.toLowerCase() ?? "";
    if (url.includes("openrouter.ai")) return "openrouter";
    if (url.includes("api.deepseek.com") || url.includes("deepseek.com")) return "deepseek";
    return "none";
  }

  captureFromResponseHeaders(providerId: string, headers: Record<string, string>) {
    if (providerId !== PRIMARY_OAUTH_PROVIDER_ID) return;
    const parsed = parseCodexResponseHeaders(headers, this.cache.get(providerId));
    if (!parsed) return;
    this.cache.set(providerId, parsed);
    this.emit(providerId, parsed);
  }

  clear(providerId: string) {
    this.cache.delete(providerId);
    this.emit(providerId, null);
  }

  async refresh(providerId: string, force: boolean = false): Promise<ProviderQuotaSnapshot | null> {
    const adapter = this.resolveAdapter(providerId);
    if (adapter === "none") return null;

    const cached = this.cache.get(providerId);
    if (!force && cached && !cached.error && Date.now() - cached.updatedAtMs < SOFT_TTL_MS) {
      this.emit(providerId, cached);
      return cached;
    }

    const existingPromise = this.inflight.get(providerId);
    if (existingPromise) return existingPromise;

    const runner =
      adapter === "codex"
        ? () => this.fetchCodexUsage()
        : adapter === "openrouter"
          ? () => this.fetchOpenRouterQuota(providerId)
          : () => this.fetchDeepSeekBalance(providerId);

    const job = runner().finally(() => {
      this.inflight.delete(providerId);
    });
    this.inflight.set(providerId, job);
    return job;
  }

  async consumeResetCredit(
    providerId: string,
    creditId?: string,
  ): Promise<{ ok: boolean; message: string; windowsReset?: number }> {
    if (providerId !== PRIMARY_OAUTH_PROVIDER_ID) {
      return { ok: false, message: "该订阅暂不支持重置卡功能" };
    }
    const auth = await this.resolveCodexAuth();
    if (!auth) {
      return { ok: false, message: "请先登录 ChatGPT Codex 账号" };
    }

    let targetId = creditId?.trim() || "";
    if (!targetId) {
      const latest = await this.fetchCodexResetCredits(auth.headers);
      targetId = latest?.credits.find((c) => c.status === "available")?.creditId ?? latest?.credits[0]?.creditId ?? "";
    }
    if (!targetId) {
      return { ok: false, message: "当前账号没有可用的重置卡" };
    }

    try {
      const resp = await fetch(CODEX_RESET_CONSUME_URL, {
        method: "POST",
        headers: {
          ...auth.headers,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          credit_id: targetId,
          redeem_request_id: generateRequestUuid(),
        }),
      });

      const bodyText = await resp.text();
      let parsed: any = null;
      try {
        parsed = bodyText ? JSON.parse(bodyText) : null;
      } catch {
        parsed = null;
      }

      if (!resp.ok) {
        const detail =
          parsed?.detail?.message ??
          parsed?.detail ??
          parsed?.error?.message ??
          parsed?.message ??
          `HTTP ${resp.status}`;
        return { ok: false, message: `使用重置卡失败：${String(detail)}` };
      }

      const windowsReset = parseFiniteNumber(parsed?.windows_reset ?? parsed?.windowsReset);
      await this.refresh(providerId, true);
      return {
        ok: true,
        message: "重置卡已生效，当前限额周期已重置",
        windowsReset: windowsReset !== undefined ? Math.round(windowsReset) : undefined,
      };
    } catch (e: any) {
      return { ok: false, message: `网络异常，重置卡请求失败：${String(e?.message ?? e)}` };
    }
  }

  private async resolveCodexAuth(): Promise<{ headers: Record<string, string> } | null> {
    const providerId = PRIMARY_OAUTH_PROVIDER_ID;
    const cred = (await this.credentials.read(providerId)) as any;
    if (!cred || cred.type !== "oauth") {
      this.clear(providerId);
      return null;
    }

    let accessToken: string | undefined;
    try {
      const resolved = await this.models.getAuth(providerId);
      accessToken = resolved?.auth?.apiKey;
    } catch {
      accessToken = undefined;
    }
    if (!accessToken) {
      accessToken = typeof cred.access === "string" ? cred.access : undefined;
    }
    if (!accessToken) return null;

    const accountId =
      (typeof cred.accountId === "string" && cred.accountId) || decodeJwtAccountId(accessToken);

    const headers: Record<string, string> = {
      Authorization: `Bearer ${accessToken}`,
      Accept: "application/json",
      "User-Agent": "pi",
    };
    if (accountId) {
      headers["ChatGPT-Account-Id"] = accountId;
    }
    return { headers };
  }

  private async fetchCodexResetCredits(headers: Record<string, string>): Promise<ResetCreditsInfo | undefined> {
    try {
      const resp = await fetch(CODEX_RESET_CREDITS_URL, { method: "GET", headers });
      if (!resp.ok) return undefined;
      const data = await resp.json();
      return parseCodexResetCreditsJson(data);
    } catch {
      return undefined;
    }
  }

  private async fetchCodexUsage(): Promise<ProviderQuotaSnapshot | null> {
    const providerId = PRIMARY_OAUTH_PROVIDER_ID;
    const prev = this.cache.get(providerId);
    const auth = await this.resolveCodexAuth();
    if (!auth) return null;

    try {
      const [usageResp, resetCredits] = await Promise.all([
        fetch(CODEX_USAGE_URL, { method: "GET", headers: auth.headers }),
        this.fetchCodexResetCredits(auth.headers),
      ]);

      if (!usageResp.ok) {
        const errHint = usageResp.status === 429 ? "查询过于频繁，稍后刷新" : `查询失败 (${usageResp.status})`;
        const fallback: ProviderQuotaSnapshot = prev
          ? { ...prev, resetCredits: resetCredits ?? prev.resetCredits, error: errHint }
          : {
              providerId,
              kind: "subscription",
              limitReached: false,
              resetCredits,
              updatedAtMs: Date.now(),
              error: errHint,
            };
        this.cache.set(providerId, fallback);
        this.emit(providerId, fallback);
        return fallback;
      }

      const data = await usageResp.json();
      const parsed = parseCodexUsageJson(data, Date.now(), resetCredits ?? prev?.resetCredits);
      if (parsed) {
        this.cache.set(providerId, parsed);
        this.emit(providerId, parsed);
        return parsed;
      }
      return prev ?? null;
    } catch {
      const errHint = "网络异常，无法查询额度";
      const fallback: ProviderQuotaSnapshot = prev
        ? { ...prev, error: errHint }
        : { providerId, kind: "subscription", limitReached: false, updatedAtMs: Date.now(), error: errHint };
      this.cache.set(providerId, fallback);
      this.emit(providerId, fallback);
      return fallback;
    }
  }

  private async fetchOpenRouterQuota(providerId: string): Promise<ProviderQuotaSnapshot | null> {
    const prev = this.cache.get(providerId);
    const cred = (await this.credentials.read(providerId)) as any;
    const apiKey = typeof cred?.key === "string" ? cred.key.trim() : "";
    if (!apiKey) return null;

    const headers = {
      Authorization: `Bearer ${apiKey}`,
      Accept: "application/json",
    };

    try {
      let creditsJson: any = null;
      let keyJson: any = null;
      let lastStatus = 0;

      const [cResp, kResp] = await Promise.all([
        fetch(OPENROUTER_CREDITS_URL, { method: "GET", headers }).catch(() => null),
        fetch(OPENROUTER_KEY_URL, { method: "GET", headers }).catch(() => null),
      ]);

      if (cResp) {
        lastStatus = cResp.status;
        if (cResp.ok) creditsJson = await cResp.json().catch(() => null);
      }
      if (kResp) {
        if (!creditsJson) lastStatus = kResp.status;
        if (kResp.ok) keyJson = await kResp.json().catch(() => null);
      }

      const parsed = parseOpenRouterQuotaJson(providerId, creditsJson, keyJson);
      if (parsed) {
        this.cache.set(providerId, parsed);
        this.emit(providerId, parsed);
        return parsed;
      }

      const errHint = lastStatus > 0 ? `余额查询失败 (${lastStatus})` : "无法查询余额";
      const fallback: ProviderQuotaSnapshot = prev
        ? { ...prev, error: errHint }
        : { providerId, kind: "balance", limitReached: false, updatedAtMs: Date.now(), error: errHint };
      this.cache.set(providerId, fallback);
      this.emit(providerId, fallback);
      return fallback;
    } catch {
      const errHint = "网络异常，无法查询余额";
      const fallback: ProviderQuotaSnapshot = prev
        ? { ...prev, error: errHint }
        : { providerId, kind: "balance", limitReached: false, updatedAtMs: Date.now(), error: errHint };
      this.cache.set(providerId, fallback);
      this.emit(providerId, fallback);
      return fallback;
    }
  }

  private async fetchDeepSeekBalance(providerId: string): Promise<ProviderQuotaSnapshot | null> {
    const prev = this.cache.get(providerId);
    const cred = (await this.credentials.read(providerId)) as any;
    const apiKey = typeof cred?.key === "string" ? cred.key.trim() : "";
    if (!apiKey) return null;

    try {
      const resp = await fetch(DEEPSEEK_BALANCE_URL, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          Accept: "application/json",
        },
      });
      if (!resp.ok) {
        const errHint = `余额查询失败 (${resp.status})`;
        const fallback: ProviderQuotaSnapshot = prev
          ? { ...prev, error: errHint }
          : { providerId, kind: "balance", limitReached: false, updatedAtMs: Date.now(), error: errHint };
        this.cache.set(providerId, fallback);
        this.emit(providerId, fallback);
        return fallback;
      }
      const data = await resp.json();
      const parsed = parseDeepSeekBalanceJson(providerId, data);
      if (parsed) {
        this.cache.set(providerId, parsed);
        this.emit(providerId, parsed);
        return parsed;
      }
      return prev ?? null;
    } catch {
      const errHint = "网络异常，无法查询余额";
      const fallback: ProviderQuotaSnapshot = prev
        ? { ...prev, error: errHint }
        : { providerId, kind: "balance", limitReached: false, updatedAtMs: Date.now(), error: errHint };
      this.cache.set(providerId, fallback);
      this.emit(providerId, fallback);
      return fallback;
    }
  }

  private emit(providerId: string, quota: ProviderQuotaSnapshot | null) {
    bridge({
      type: "quota",
      provider: providerId,
      quota,
    });
  }
}
