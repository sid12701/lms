/**
 * Mock router — typed dispatch pipeline.
 *
 * Pipeline (in order):
 *   1. apply latency
 *   2. consult scenario.shouldFail
 *   3. match route (extract path params)
 *   4. for mutations: idempotency-key cache lookup → return cached
 *   5. resolve correlationId (header or fresh)
 *   6. invoke handler against the singleton db
 *   7. for mutations: cache the result by idempotency-key
 *   8. parse the result with the supplied Zod parser (drift detection)
 *   9. persist db (debounced)
 *
 * Errors thrown by handlers are caught and wrapped via `toMockError(...)`
 * and re-thrown — callers (`api.*` modules) can catch and decide whether to
 * surface envelope vs raise. The router itself does not return envelopes.
 */
import type { z } from "zod";
import { createIdempotencyCache } from "@/lib/idempotency";
import { MockError, NotFoundError, newCorrelationId, toMockError, type ErrorCode } from "./errors";
import { applyLatency } from "./latency";
import { scenario } from "./scenarios";
import { saveDb } from "./db/persistence";
import { getDb, type MockDb } from "./db/state";

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface MockRequest {
  method: HttpMethod;
  /** e.g. "/api/v1/auth/login". */
  path: string;
  body?: unknown;
  headers?: Record<string, string>;
  query?: Record<string, string | string[]>;
  /** Path params extracted by the router from the URL pattern. */
  params?: Record<string, string>;
}

export type Handler = (
  req: MockRequest,
  db: MockDb,
  correlationId: string,
) => Promise<unknown> | unknown;

interface Route {
  method: HttpMethod;
  pattern: RegExp;
  paramNames: string[];
  handler: Handler;
  mutating: boolean;
  rawPath: string;
}

const routes: Route[] = [];

/**
 * Convert a `/api/v1/loans/:id` style path into a regex + ordered param-name list.
 */
function compilePattern(rawPath: string): { pattern: RegExp; paramNames: string[] } {
  const paramNames: string[] = [];
  // Escape regex metacharacters except for ":" and "/".
  const escaped = rawPath.replace(/[.+*?^$()[\]{}|\\]/g, "\\$&");
  const regexStr = escaped.replace(/:([A-Za-z_][A-Za-z0-9_]*)/g, (_match, name: string) => {
    paramNames.push(name);
    return "([^/]+)";
  });
  return { pattern: new RegExp(`^${regexStr}$`), paramNames };
}

export interface RegisterRouteOptions {
  /** Mutations consume idempotency keys + cache results. Default false. */
  mutating?: boolean;
}

export function registerRoute(
  method: HttpMethod,
  rawPath: string,
  handler: Handler,
  opts: RegisterRouteOptions = {},
): void {
  const { pattern, paramNames } = compilePattern(rawPath);
  routes.push({
    method,
    pattern,
    paramNames,
    handler,
    mutating: opts.mutating ?? false,
    rawPath,
  });
}

/** Test/dev only — clear all registered routes. */
export function _resetRoutesForTests(): void {
  routes.length = 0;
}

function matchRoute(
  method: HttpMethod,
  path: string,
): {
  route: Route;
  params: Record<string, string>;
} | null {
  for (const route of routes) {
    if (route.method !== method) continue;
    const m = route.pattern.exec(path);
    if (!m) continue;
    const params: Record<string, string> = {};
    route.paramNames.forEach((name, i) => {
      // Index 0 is the full match — params start at 1.
      const value = m[i + 1];
      if (value !== undefined) params[name] = decodeURIComponent(value);
    });
    return { route, params };
  }
  return null;
}

// ─── Idempotency cache (singleton, 30s TTL per BR-5) ─────────────────────────
const idempotencyCache = createIdempotencyCache(30_000);

/** Test helper — empty the idempotency cache. */
export function _clearIdempotencyCacheForTests(): void {
  idempotencyCache.clear();
}

function resolveCorrelationId(req: MockRequest): string {
  return req.headers?.["X-Correlation-Id"] ?? newCorrelationId();
}

function readIdempotencyKey(req: MockRequest): string | undefined {
  return req.headers?.["Idempotency-Key"];
}

/**
 * Typed dispatch. Runs the pipeline and parses the handler's return value
 * with the supplied Zod parser. Throws a `MockError` on any failure path —
 * callers decide between envelope-style or raise-style handling.
 */
export async function dispatch<T>(req: MockRequest, parser: z.ZodType<T>): Promise<T> {
  await applyLatency();

  const correlationId = resolveCorrelationId(req);

  const failDecision = scenario.shouldFail({ method: req.method, path: req.path });
  if (failDecision.fail) {
    throw new MockError(
      failDecision.kind as ErrorCode,
      failDecision.kind === "FORBIDDEN" ? 403 : 500,
      correlationId,
      failDecision.message,
    );
  }

  const matched = matchRoute(req.method, req.path);
  if (!matched) {
    throw new NotFoundError(correlationId, `no route registered for ${req.method} ${req.path}`);
  }

  const { route, params } = matched;
  const enrichedReq: MockRequest = { ...req, params };

  const idemKey = readIdempotencyKey(req);
  if (route.mutating && idemKey) {
    const cached = idempotencyCache.lookup<unknown>(idemKey);
    if (cached !== undefined) {
      return parser.parse(cached);
    }
  }

  const db = getDb();
  let result: unknown;
  try {
    result = await route.handler(enrichedReq, db, correlationId);
  } catch (err) {
    throw toMockError(err, correlationId);
  }

  if (route.mutating && idemKey) {
    idempotencyCache.cache(idemKey, result);
  }

  // Schema-drift detection: dev-time fail-loud if the handler returned
  // something the contract doesn't expect.
  const parsed = parser.parse(result);

  if (route.mutating) {
    saveDb(db);
  }

  return parsed;
}
