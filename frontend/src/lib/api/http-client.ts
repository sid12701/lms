/**
 * Live backend HTTP transport.
 *
 * - Builds URLs from `VITE_API_BASE_URL` (defaults to http://localhost:8080).
 * - Injects `Authorization: Bearer <token>` for authenticated requests.
 * - Refuses credential-bearing requests to any origin other than the API base.
 * - Normalises Spring's error envelope into a typed `ApiError`.
 * - On 401, calls a registered refresh callback once and retries the request.
 * - De-duplicates concurrent GETs by URL+token.
 * - Sends cookies (refresh token lives in an httpOnly cookie set by the backend).
 */
import { getStoredAccessToken } from "@/lib/api/session-storage";
import { captureAuthIntent, isStaleIntent } from "@/features/auth/auth-coordinator";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const API_ORIGIN = new URL(API_BASE_URL).origin;

type QueryParamValue = string | number | boolean | readonly string[] | null | undefined;

let onUnauthorizedRefresh: (() => Promise<string | null>) | null = null;

/**
 * M21 — a deduped GET is shared fan-in state: the underlying fetch carries a
 * private AbortController (still deadline-bounded inside `performFetch`), and
 * each caller subscribes with its own signal. One caller's cancellation only
 * detaches that caller — the shared fetch is aborted only when its LAST
 * subscriber has left, so a cancelled navigation can never silently kill
 * another component's live read.
 */
interface InflightJsonRequest {
  controller: AbortController;
  promise: Promise<unknown>;
  subscribers: number;
}

const inFlightJsonRequests = new Map<string, InflightJsonRequest>();

export function setRefreshCallback(callback: (() => Promise<string | null>) | null): void {
  onUnauthorizedRefresh = callback;
}

export class ApiError extends Error {
  status: number;
  body: string;
  code: string | null;
  retryAfterSeconds: number | null;
  /**
   * Transport settlement evidence: true only when this error was built
   * from an actually received HTTP response (headers observed, browser
   * cookie jar already updated). Locally constructed errors (validation,
   * unsettled flows) leave this false so cookie ordering treats them as
   * network-uncertain and retains the orphan marker.
   */
  settled: boolean;

  constructor(
    message: string,
    status: number,
    body: string,
    code: string | null,
    retryAfterSeconds: number | null = null,
    settled = false,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds;
    this.settled = settled;
  }
}

const HTTP_SETTLED = Symbol.for("bhawana.lms.httpSettled");

/**
 * Mark a non-ApiError throw as observed AFTER a real HTTP response was
 * received (e.g. JSON parse/contract failure while reading the body). The
 * cookie jar already reflects that response, so ordering may settle.
 */
export function markHttpSettled<T>(error: T): T {
  if (error && typeof error === "object") {
    try {
      (error as Record<symbol, unknown>)[HTTP_SETTLED] = true;
    } catch {
      // Best effort only.
    }
  }
  return error;
}

/**
 * Explicit transport settlement evidence from the actual response boundary.
 * Walks the cause chain (SessionRestoreError wraps the root cause).
 */
export function isHttpSettled(error: unknown, depth = 0): boolean {
  if (!error || typeof error !== "object" || depth > 5) return false;
  const record = error as Record<symbol, unknown> & { cause?: unknown };
  if (record[HTTP_SETTLED] === true) return true;
  if (error instanceof ApiError && error.settled) return true;
  return record.cause !== undefined ? isHttpSettled(record.cause, depth + 1) : false;
}

function readRetryAfterSeconds(response: Response): number | null {
  const header = response.headers.get("Retry-After");
  if (!header) return null;
  const parsed = Number.parseInt(header, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function buildUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path;
  return `${API_BASE_URL}${path.startsWith("/") ? path : `/${path}`}`;
}

/** Resolve a path against the configured API base and return a concrete URL. */
function resolveUrl(path: string): URL {
  return new URL(buildUrl(path), API_BASE_URL);
}

function assertSameOriginApiUrl(url: URL): void {
  if (url.origin !== API_ORIGIN) {
    throw new Error("Refusing cross-origin authenticated request");
  }
}

/**
 * Unauthenticated fetch to an arbitrary URL. Never attaches credentials, cookies,
 * Authorization, or Idempotency-Key. Use for legitimate third-party calls only.
 */
export async function fetchExternal(url: string | URL, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.delete("Authorization");
  headers.delete("Idempotency-Key");
  return fetch(url, {
    ...init,
    headers,
    credentials: "omit",
  });
}

/** Spring {@code ApiError} envelope emitted by {@code GlobalExceptionHandler}. */
type ApiErrorEnvelope = {
  code?: string;
  errorCode?: string;
  error?: string;
  message?: string;
};

function readResponseError(body: string): { message: string; code: string | null } {
  if (!body.trim()) return { message: "Request failed.", code: null };
  try {
    const parsed = JSON.parse(body) as ApiErrorEnvelope;
    const code =
      (typeof parsed.code === "string" && parsed.code) ||
      (typeof parsed.errorCode === "string" && parsed.errorCode) ||
      (typeof parsed.error === "string" && parsed.error) ||
      null;
    const message =
      (typeof parsed.message === "string" && parsed.message) ||
      (typeof parsed.error === "string" && parsed.error) ||
      body;
    return { message, code };
  } catch {
    return { message: body, code: null };
  }
}

function readFilenameFromContentDisposition(value: string | null): string | null {
  if (!value) return null;
  const utf8 = value.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8?.[1]) return decodeURIComponent(utf8[1]);
  const quoted = value.match(/filename="([^"]+)"/i);
  if (quoted?.[1]) return quoted[1];
  const simple = value.match(/filename=([^;]+)/i);
  return simple?.[1]?.trim() ?? null;
}

export function buildQueryPath(path: string, params: Record<string, QueryParamValue>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v == null || v === "") return;
    // H29 — arrays serialize as repeated params (`?status=A&status=B`) so a
    // multi-select filter reaches the server instead of dying at the client.
    if (Array.isArray(v)) {
      for (const item of v) {
        if (item == null || item === "") continue;
        search.append(k, String(item));
      }
      return;
    }
    search.set(k, String(v));
  });
  const qs = search.toString();
  return qs ? `${path}?${qs}` : path;
}

/**
 * M21 — request deadline budgets by class. Every request carries a deadline
 * (callers may widen it via `timeoutMs`); the budget composes with the
 * caller's AbortSignal rather than replacing it. `transfer` stays generous so
 * ordinary uploads/downloads are never killed by a read-sized budget.
 */
export type RequestClass = "default" | "auth" | "transfer";

const REQUEST_DEADLINE_MS: Record<RequestClass, number> = {
  default: 30_000,
  // Auth exchanges serialize through the cookie lock; still bounded so a hung
  // network cannot pin the auth coordinator forever.
  auth: 20_000,
  transfer: 180_000,
};

export interface RequestOptions {
  authenticated?: boolean;
  accessToken?: string;
  idempotencyKey?: string;
  /** Disable the global 401 refresh callback when validating a freshly minted token. */
  refreshOnUnauthorized?: boolean;
  /** When false, concurrent GETs are not coalesced (use for frequently invalidated reads). */
  dedupe?: boolean;
  /** Deadline class for the request; defaults to "default" (30s). */
  requestClass?: RequestClass;
  /** Explicit deadline override in ms — wins over `requestClass`. */
  timeoutMs?: number;
  _retried?: boolean;
}

type FetchRequestOptions = RequestOptions & {
  responseType: "json" | "blob";
};

function applyAuthHeaders(
  headers: Headers,
  options: FetchRequestOptions,
  init: RequestInit,
): { authenticated: boolean; accessToken: string | null } {
  const authenticated = options.authenticated ?? true;
  const accessToken = authenticated ? (options.accessToken ?? getStoredAccessToken()) : null;

  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (options.idempotencyKey) headers.set("Idempotency-Key", options.idempotencyKey);

  return { authenticated, accessToken };
}

async function executeFetch(url: URL, init: RequestInit, headers: Headers): Promise<Response> {
  return fetch(url, {
    ...init,
    headers,
    credentials: "include",
  });
}

function resolveDeadlineMs(options: RequestOptions): number {
  return options.timeoutMs ?? REQUEST_DEADLINE_MS[options.requestClass ?? "default"];
}

/**
 * Compose the caller's signal with the request deadline into one signal for
 * `fetch`. Returns the composed signal plus a `timedOut()` probe (so the
 * caller can distinguish a deadline abort from a user/navigation abort) and a
 * `cleanup()` that MUST run once the request has fully settled — it clears
 * the timer and detaches the caller listener on every outcome.
 */
function composeRequestSignal(
  callerSignal: AbortSignal | null | undefined,
  deadlineMs: number,
): { signal: AbortSignal; timedOut: () => boolean; cleanup: () => void } {
  const controller = new AbortController();
  let timedOut = false;
  const onCallerAbort = () => controller.abort(callerSignal?.reason);
  if (callerSignal) {
    if (callerSignal.aborted) {
      onCallerAbort();
    } else {
      callerSignal.addEventListener("abort", onCallerAbort, { once: true });
    }
  }
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort(new DOMException("The request timed out.", "TimeoutError"));
  }, deadlineMs);
  // Keep Node/Vitest timers from holding the process open; harmless in browsers.
  if (typeof timer === "object" && "unref" in timer) {
    (timer as { unref: () => void }).unref();
  }
  const cleanup = () => {
    clearTimeout(timer);
    callerSignal?.removeEventListener("abort", onCallerAbort);
  };
  return { signal: controller.signal, timedOut: () => timedOut, cleanup };
}

function isAbortError(error: unknown): boolean {
  return (
    error instanceof DOMException && (error.name === "AbortError" || error.name === "TimeoutError")
  );
}

/**
 * M21 — a deadline abort is a distinct, readable error (never mistaken for a
 * caller cancellation). For mutations the timeout is deliberately uncertain:
 * the request may still have landed, so the message steers the operator to
 * verify before replaying — the idempotency key already makes a safe retry
 * possible.
 */
function requestTimeoutError(init: RequestInit): ApiError {
  const method = (init.method ?? "GET").toUpperCase();
  const message =
    method === "GET" || method === "HEAD"
      ? "The request took too long. Check your connection and try again."
      : "The request timed out. The server may still have processed it — verify the record's status before retrying.";
  return new ApiError(message, 0, "", "REQUEST_TIMEOUT", null, false);
}

async function performFetch(
  path: string,
  init: RequestInit = {},
  options: FetchRequestOptions,
): Promise<Response> {
  const url = resolveUrl(path);
  // All API-client traffic (including cookie-bearing unauthenticated calls) must stay same-origin.
  assertSameOriginApiUrl(url);

  // Capture the original operation's full owning intent (Lamport epoch
  // + writer) before async work. Every 401/428/success/failure replay path
  // obeys it — epoch-only fencing would let a same-epoch different-writer
  // identity slip through.
  const requestIntent = captureAuthIntent();
  const headers = new Headers(init.headers);
  const { authenticated } = applyAuthHeaders(headers, options, init);

  const response = await executeFetch(url, init, headers);

  if (
    response.status === 401 &&
    authenticated &&
    options.refreshOnUnauthorized !== false &&
    !options._retried &&
    onUnauthorizedRefresh
  ) {
    // Stale 401s never trigger refresh or retry for a new identity.
    if (isStaleIntent(requestIntent)) return response;
    const newToken = await onUnauthorizedRefresh();
    // The refresh may have resolved for a superseded identity (logout/login
    // advanced while it was in flight). Never issue authenticated retry work
    // for the new identity from a stale operation.
    if (isStaleIntent(requestIntent)) return response;
    if (newToken) {
      return performFetch(path, init, { ...options, accessToken: newToken, _retried: true });
    }
  }

  if (response.status === 428 && authenticated && typeof window !== "undefined") {
    // Stale 428s must not redirect the new identity to change-password.
    if (isStaleIntent(requestIntent)) {
      throw new ApiError(
        "Password change required before continuing.",
        428,
        await response.text(),
        "PASSWORD_CHANGE_REQUIRED",
        null,
        true,
      );
    }
    if (!window.location.pathname.startsWith("/change-password")) {
      window.location.assign("/change-password");
    }
    throw new ApiError(
      "Password change required before continuing.",
      428,
      await response.text(),
      "PASSWORD_CHANGE_REQUIRED",
      null,
      true,
    );
  }

  return response;
}

/**
 * 409 IDEMPOTENCY_IN_PROGRESS is the only retryable conflict: the duplicate's
 * idempotency key is still owned by an in-flight request and the backend's
 * bounded Retry-After says when to ask again. Payload conflicts
 * (IDEMPOTENCY_CONFLICT) and recovery-required conflicts are terminal — they
 * never carry a usable Retry-After and must not be retried as if transient.
 */
function isRetryableConflict(status: number, code: string | null): boolean {
  return status === 409 && code === "IDEMPOTENCY_IN_PROGRESS";
}

async function throwIfNotOk(response: Response): Promise<void> {
  if (response.ok) return;
  const errorBody = await response.text();
  const { message, code } = readResponseError(errorBody);
  const retryAfterSeconds =
    response.status === 429 || isRetryableConflict(response.status, code)
      ? readRetryAfterSeconds(response)
      : null;
  // Built from a received response: settled transport evidence.
  throw new ApiError(
    message || `Request failed with status ${response.status}`,
    response.status,
    errorBody,
    code,
    retryAfterSeconds,
    true,
  );
}

async function readJsonBody<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) return (await response.text()) as T;
  return (await response.json()) as T;
}

export interface JsonWithHeaders<T> {
  data: T;
  headers: Headers;
}

/**
 * Coalesce identical concurrent GETs: when `key` is non-null and a matching
 * request is already in flight, subscribe to it instead of issuing a second
 * network call. Entries self-evict once the shared request settles. Caller
 * signals are per-subscriber — they detach, never propagate to the shared
 * fetch until no subscriber remains (see `InflightJsonRequest`).
 */
function dedupedJsonRequest<T>(
  key: string | null,
  callerSignal: AbortSignal | null | undefined,
  run: (signal: AbortSignal | undefined) => Promise<T>,
): Promise<T> {
  if (!key) return run(callerSignal ?? undefined);
  const existing = inFlightJsonRequests.get(key);
  if (existing) return subscribeToInflight(key, existing, callerSignal);
  const controller = new AbortController();
  const entry: InflightJsonRequest = {
    controller,
    promise: null as unknown as Promise<unknown>,
    subscribers: 0,
  };
  entry.promise = run(controller.signal).finally(() => {
    // Evict only if this record is still the live one — a successor may have
    // already been registered for the same key after this entry settled.
    if (inFlightJsonRequests.get(key) === entry) inFlightJsonRequests.delete(key);
  });
  inFlightJsonRequests.set(key, entry);
  return subscribeToInflight(key, entry, callerSignal);
}

function subscribeToInflight<T>(
  key: string,
  entry: InflightJsonRequest,
  callerSignal: AbortSignal | null | undefined,
): Promise<T> {
  if (callerSignal?.aborted) {
    return Promise.reject(callerSignal.reason ?? new DOMException("Aborted", "AbortError"));
  }
  entry.subscribers += 1;
  let detach: (() => void) | undefined;
  const detached = new Promise<never>((_resolve, reject) => {
    if (!callerSignal) return;
    const onAbort = () => reject(callerSignal.reason ?? new DOMException("Aborted", "AbortError"));
    callerSignal.addEventListener("abort", onAbort, { once: true });
    detach = () => callerSignal.removeEventListener("abort", onAbort);
  });
  return Promise.race([entry.promise as Promise<T>, detached]).finally(() => {
    detach?.();
    entry.subscribers -= 1;
    if (entry.subscribers === 0) {
      // Last subscriber left (cancelled or the request settled): stop the
      // shared fetch if it is still running and drop any dead map entry.
      entry.controller.abort();
      if (inFlightJsonRequests.get(key) === entry) inFlightJsonRequests.delete(key);
    }
  });
}

export function requestJsonWithHeaders<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<JsonWithHeaders<T>> {
  return dedupedJsonRequest(
    buildJsonDedupeKey(path, init, options, "json-with-headers"),
    init.signal,
    (signal) => performJsonRequestWithHeaders<T>(path, { ...init, signal }, options),
  );
}

async function performJsonRequestWithHeaders<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<JsonWithHeaders<T>> {
  // The deadline covers the whole exchange — headers AND body — so a stalled
  // stream cannot outlive the budget.
  const deadline = composeRequestSignal(init.signal, resolveDeadlineMs(options));
  try {
    const response = await performFetch(
      path,
      { ...init, signal: deadline.signal },
      {
        ...options,
        responseType: "json",
      },
    );
    await throwIfNotOk(response);
    const data = await readJsonBody<T>(response);
    return { data, headers: response.headers };
  } catch (error) {
    if (deadline.timedOut()) throw requestTimeoutError(init);
    // A caller/navigation abort is not an API error — propagate untouched.
    if (isAbortError(error)) throw error;
    // A response was received (jar already updated); even local
    // parse/contract failures afterwards must not look network-uncertain.
    throw markHttpSettled(error);
  } finally {
    deadline.cleanup();
  }
}

export function requestJson<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  return dedupedJsonRequest(
    buildJsonDedupeKey(path, init, options, "json"),
    init.signal,
    (signal) => performJsonRequest<T>(path, { ...init, signal }, options),
  );
}

type JsonDedupeMode = "json" | "json-with-headers";

function buildJsonDedupeKey(
  path: string,
  init: RequestInit,
  options: RequestOptions,
  mode: JsonDedupeMode,
): string | null {
  if (options.dedupe === false) return null;
  const method = (init.method ?? "GET").toUpperCase();
  if (method !== "GET" || init.body || options._retried) return null;
  const authenticated = options.authenticated ?? true;
  const token = authenticated ? (options.accessToken ?? getStoredAccessToken()) : null;
  return `${mode}:${authenticated ? "auth" : "anon"}:${token ?? ""}:${path}`;
}

async function performJsonRequest<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  const deadline = composeRequestSignal(init.signal, resolveDeadlineMs(options));
  try {
    const response = await performFetch(
      path,
      { ...init, signal: deadline.signal },
      {
        ...options,
        responseType: "json",
      },
    );
    await throwIfNotOk(response);
    return await readJsonBody<T>(response);
  } catch (error) {
    if (deadline.timedOut()) throw requestTimeoutError(init);
    if (isAbortError(error)) throw error;
    throw markHttpSettled(error);
  } finally {
    deadline.cleanup();
  }
}

export async function requestBlob(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<{ blob: Blob; filename: string | null }> {
  // Downloads get the transfer budget unless the caller overrides.
  const deadline = composeRequestSignal(
    init.signal,
    resolveDeadlineMs({ requestClass: "transfer", ...options }),
  );
  try {
    const response = await performFetch(
      path,
      { ...init, signal: deadline.signal },
      {
        ...options,
        responseType: "blob",
      },
    );
    await throwIfNotOk(response);

    return {
      blob: await response.blob(),
      filename: readFilenameFromContentDisposition(response.headers.get("content-disposition")),
    };
  } catch (error) {
    if (deadline.timedOut()) throw requestTimeoutError(init);
    if (isAbortError(error)) throw error;
    throw markHttpSettled(error);
  } finally {
    deadline.cleanup();
  }
}
