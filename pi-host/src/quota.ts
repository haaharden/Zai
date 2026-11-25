import type { CredentialStore, Models } from "@earendil-works/pi-ai";
import { bridge } from "./bridge.ts";
import { PRIMARY_OAUTH_PROVIDER_ID } from "./providers.ts";

export interface QuotaWindowSnapshot {
  usedPercent: number;
  remainingPercent: number;
  windowMinutes: number;
  resetAtMs: number;
}

export interface ProviderQuotaSnapshot {
  providerId: string;
  planType?: string;
  email?: string;
  primary?: QuotaWindowSnapshot;
  secondary?: QuotaWindowSnapshot;
  limitReached: boolean;
  creditsUnlimited?: boolean;
  creditsBalance?: string;
  updatedAtMs: number;
  error?: string;
}

const CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
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

export function parseCodexUsageJson(json: any, nowMs: number = Date.now()): ProviderQuotaSnapshot | null {
  if (!json || typeof json !== "object") return null;
  const rateLimit = json.rate_limit ?? json.rateLimit;
  let primary = parseWindowObject(rateLimit?.primary_window ?? rateLimit?.primaryWindow, nowMs);
  let secondary = parseWindowObject(rateLimit?.secondary_window ?? rateLimit?.secondaryWindow, nowMs);

  // If primary_window is weekly (e.g. 7 days) and an additional 5h window exists, expose both clearly
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

  // Normalize ordering so shorter window (e.g. 5h) is always primary and longer window (e.g. 7d) is secondary
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
    planType,
    email,
    primary,
    secondary,
    limitReached,
    creditsUnlimited: typeof credits?.unlimited === "boolean" ? credits.unlimited : undefined,
    creditsBalance,
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

  return {
    providerId: PRIMARY_OAUTH_PROVIDER_ID,
    planType,
    email: existing?.email,
    primary: primary ?? existing?.primary,
    secondary: secondary ?? existing?.secondary,
    limitReached:
      (primary?.remainingPercent === 0) || (secondary?.remainingPercent === 0),
    creditsUnlimited,
    creditsBalance: rawBal || existing?.creditsBalance,
    updatedAtMs: nowMs,
  };
}

export class QuotaTracker {
  private readonly cache = new Map<string, ProviderQuotaSnapshot>();
  private readonly inflight = new Map<string, Promise<ProviderQuotaSnapshot | null>>();

  constructor(
    private readonly models: Models,
    private readonly credentials: CredentialStore,
  ) {}

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
    if (providerId !== PRIMARY_OAUTH_PROVIDER_ID) return null;

    const cached = this.cache.get(providerId);
    if (!force && cached && !cached.error && Date.now() - cached.updatedAtMs < SOFT_TTL_MS) {
      this.emit(providerId, cached);
      return cached;
    }

    const existingPromise = this.inflight.get(providerId);
    if (existingPromise) return existingPromise;

    const job = this.fetchCodexUsage().finally(() => {
      this.inflight.delete(providerId);
    });
    this.inflight.set(providerId, job);
    return job;
  }

  private async fetchCodexUsage(): Promise<ProviderQuotaSnapshot | null> {
    const providerId = PRIMARY_OAUTH_PROVIDER_ID;
    const prev = this.cache.get(providerId);

    const cred = (await this.credentials.read(providerId)) as any;
    if (!cred || cred.type !== "oauth") {
      this.clear(providerId);
      return null;
    }

    // Resolve through models.getAuth so an expired access token is refreshed transparently
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

    const reqHeaders: Record<string, string> = {
      Authorization: `Bearer ${accessToken}`,
      Accept: "application/json",
      "User-Agent": "pi",
    };
    if (accountId) {
      reqHeaders["ChatGPT-Account-Id"] = accountId;
    }

    try {
      const resp = await fetch(CODEX_USAGE_URL, {
        method: "GET",
        headers: reqHeaders,
      });

      if (!resp.ok) {
        const errHint = resp.status === 429 ? "查询过于频繁，稍后刷新" : `查询失败 (${resp.status})`;
        const fallback: ProviderQuotaSnapshot = prev
          ? { ...prev, error: errHint }
          : { providerId, limitReached: false, updatedAtMs: Date.now(), error: errHint };
        this.cache.set(providerId, fallback);
        this.emit(providerId, fallback);
        return fallback;
      }

      const data = await resp.json();
      const parsed = parseCodexUsageJson(data);
      if (parsed) {
        this.cache.set(providerId, parsed);
        this.emit(providerId, parsed);
        return parsed;
      }
      return prev ?? null;
    } catch (e: any) {
      const errHint = "网络异常，无法查询额度";
      const fallback: ProviderQuotaSnapshot = prev
        ? { ...prev, error: errHint }
        : { providerId, limitReached: false, updatedAtMs: Date.now(), error: errHint };
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
