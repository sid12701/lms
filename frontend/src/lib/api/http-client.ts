/**
 * Live backend HTTP transport.
 *
 * - Builds URLs from `VITE_API_BASE_URL` (defaults to http://localhost:8080).
 * - Injects `Authorization: Bearer <token>` for authenticated requests.
 * - Normalises Spring's error envelope into a typed `ApiError`.
 * - On 401, calls a registered refresh callback once and retries the request.
 * - De-duplicates concurrent GETs by URL+token.
 * - Sends cookies (refresh token lives in an httpOnly cookie set by the backend).
 */
import { getStoredAccessToken } from "@/lib/api/session-storage";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

type QueryParamValue = string | number | boolean | null | undefined;

let onUnauthorizedRefresh: (() => Promise<string | null>) | null = null;
const inFlightJsonRequests = new Map<string, Promise<unknown>>();

export function setRefreshCallback(callback: (() => Promise<string | null>) | null): void {
  onUnauthorizedRefresh = callback;
}

export class ApiError extends Error {
  status: number;
  body: string;
  code: string | null;
  retryAfterSeconds: number | null;

  constructor(
    message: string,
    status: number,
    body: string,
    code: string | null,
    retryAfterSeconds: number | null = null,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds;
  }
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
    search.set(k, String(v));
  });
  const qs = search.toString();
  return qs ? `${path}?${qs}` : path;
}

export interface RequestOptions {
  authenticated?: boolean;
  accessToken?: string;
  idempotencyKey?: string;
  /** When false, concurrent GETs are not coalesced (use for frequently invalidated reads). */
  dedupe?: boolean;
  _retried?: boolean;
}

type FetchRequestOptions = RequestOptions & {
  responseType: "json" | "blob";
};

async function performFetch(
  path: string,
  init: RequestInit = {},
  options: FetchRequestOptions,
): Promise<Response> {
  const authenticated = options.authenticated ?? true;
  const headers = new Headers(init.headers);
  const accessToken = authenticated ? (options.accessToken ?? getStoredAccessToken()) : null;

  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (options.idempotencyKey) headers.set("Idempotency-Key", options.idempotencyKey);

  const response = await fetch(buildUrl(path), {
    ...init,
    headers,
    credentials: "include",
  });

  if (response.status === 401 && authenticated && !options._retried && onUnauthorizedRefresh) {
    const newToken = await onUnauthorizedRefresh();
    if (newToken) {
      return performFetch(path, init, { ...options, accessToken: newToken, _retried: true });
    }
  }

  if (response.status === 428 && authenticated && typeof window !== "undefined") {
    if (!window.location.pathname.startsWith("/change-password")) {
      window.location.assign("/change-password");
    }
    throw new ApiError(
      "Password change required before continuing.",
      428,
      await response.text(),
      "PASSWORD_CHANGE_REQUIRED",
    );
  }

  return response;
}

async function throwIfNotOk(response: Response): Promise<void> {
  if (response.ok) return;
  const errorBody = await response.text();
  const { message, code } = readResponseError(errorBody);
  const retryAfterSeconds = response.status === 429 ? readRetryAfterSeconds(response) : null;
  throw new ApiError(
    message || `Request failed with status ${response.status}`,
    response.status,
    errorBody,
    code,
    retryAfterSeconds,
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
 * request is already in flight, return the existing promise instead of issuing
 * a second network call. Entries self-evict once the request settles.
 */
function dedupedJsonRequest<T>(key: string | null, run: () => Promise<T>): Promise<T> {
  if (!key) return run();
  const existing = inFlightJsonRequests.get(key) as Promise<T> | undefined;
  if (existing) return existing;
  const promise = run().finally(() => inFlightJsonRequests.delete(key));
  inFlightJsonRequests.set(key, promise);
  return promise;
}

export function requestJsonWithHeaders<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<JsonWithHeaders<T>> {
  return dedupedJsonRequest(buildJsonDedupeKey(path, init, options, "json-with-headers"), () =>
    performJsonRequestWithHeaders<T>(path, init, options),
  );
}

async function performJsonRequestWithHeaders<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<JsonWithHeaders<T>> {
  const response = await performFetch(path, init, { ...options, responseType: "json" });
  await throwIfNotOk(response);
  const data = await readJsonBody<T>(response);
  return { data, headers: response.headers };
}

export function requestJson<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  return dedupedJsonRequest(buildJsonDedupeKey(path, init, options, "json"), () =>
    performJsonRequest<T>(path, init, options),
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
  const response = await performFetch(path, init, { ...options, responseType: "json" });
  await throwIfNotOk(response);
  return readJsonBody<T>(response);
}

export async function requestBlob(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<{ blob: Blob; filename: string | null }> {
  const response = await performFetch(path, init, { ...options, responseType: "blob" });
  await throwIfNotOk(response);

  return {
    blob: await response.blob(),
    filename: readFilenameFromContentDisposition(response.headers.get("content-disposition")),
  };
}
