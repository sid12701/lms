/**
 * Auth handler for the mock router.
 *
 * Implements the four endpoints app-shell needs to bootstrap:
 *   - POST /api/v1/auth/login                — username + (mock) password
 *   - POST /api/v1/auth/logout               — clears the active session
 *   - GET  /api/v1/auth/session              — returns current session or null
 *   - POST /api/v1/auth/password/change      — clears mustChangePassword flag
 *
 * Passwords are not validated. The seed exists purely so the change-password
 * flow has a target user (`temp.user` with `mustChangePassword: true`).
 *
 * The token is a literal mock-shaped string `mock.<userId>.<expiresAtIsoEpoch>`.
 * Anything beyond shape parity is out of scope for a UI-only repository.
 */
import { z } from "zod";
import { Role } from "@/schemas/role";
import { Uuid } from "@/schemas/common";
import { dispatch, registerRoute, type MockRequest } from "../router";
import { BadRequestError, UnauthorizedError, newCorrelationId } from "../errors";
import { findSeedUserByUsername } from "../db/seed";
import type { MockDb, MockSession } from "../db/state";

// ─── Schemas ─────────────────────────────────────────────────────────────────

export const SessionUser = z.object({
  id: Uuid,
  username: z.string().min(3).max(64),
  role: Role,
  lspId: Uuid.nullable(),
  mustChangePassword: z.boolean(),
});
export type SessionUser = z.infer<typeof SessionUser>;

export const Session = z.object({
  user: SessionUser,
  accessToken: z.string().min(1),
  expiresAt: z.string().datetime({ offset: true }),
});
export type Session = z.infer<typeof Session>;

export const SessionOrNull = Session.nullable();

const OkAck = z.object({ ok: z.literal(true) });
type OkAck = z.infer<typeof OkAck>;

// ─── Helpers ─────────────────────────────────────────────────────────────────

const SESSION_TTL_MS = 8 * 60 * 60 * 1000; // 8 hours

function mintToken(userId: string, expiresAt: string): string {
  return `mock.${userId}.${Date.parse(expiresAt)}`;
}

function buildSession(db: MockDb, userId: string): Session {
  const user = db.users.get(userId);
  if (!user) {
    throw new UnauthorizedError("n/a", "user no longer exists");
  }
  const expiresAt = new Date(Date.now() + SESSION_TTL_MS).toISOString();
  const accessToken = mintToken(user.id, expiresAt);
  const stored: MockSession = { userId: user.id, accessToken, expiresAt };
  db.currentSession = stored;
  return {
    user: {
      id: user.id,
      username: user.username,
      role: user.role,
      lspId: user.lspId,
      mustChangePassword: user.mustChangePassword,
    },
    accessToken,
    expiresAt,
  };
}

// ─── Handlers ────────────────────────────────────────────────────────────────

const LoginInput = z.object({
  username: z.string().min(1),
  password: z.string().min(1),
});

function loginHandler(req: MockRequest, db: MockDb, correlationId: string): Session {
  const parsed = LoginInput.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "username + password required",
      parsed.error.flatten(),
    );
  }
  const seed = findSeedUserByUsername(parsed.data.username);
  if (!seed) {
    throw new UnauthorizedError(correlationId, "invalid credentials");
  }
  // Mock-only: password is not validated. Just confirm the user exists in the
  // db (seeding can be bypassed in tests via cloneDb + setDb).
  if (!db.users.has(seed.id)) {
    throw new UnauthorizedError(correlationId, "invalid credentials");
  }
  return buildSession(db, seed.id);
}

function logoutHandler(_req: MockRequest, db: MockDb): OkAck {
  db.currentSession = null;
  return { ok: true };
}

function sessionHandler(_req: MockRequest, db: MockDb): Session | null {
  if (!db.currentSession) return null;
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    db.currentSession = null;
    return null;
  }
  return {
    user: {
      id: user.id,
      username: user.username,
      role: user.role,
      lspId: user.lspId,
      mustChangePassword: user.mustChangePassword,
    },
    accessToken: db.currentSession.accessToken,
    expiresAt: db.currentSession.expiresAt,
  };
}

const ChangePasswordInput = z.object({
  newPassword: z.string().min(8, "password must be at least 8 characters"),
});

function changePasswordHandler(req: MockRequest, db: MockDb, correlationId: string): Session {
  const parsed = ChangePasswordInput.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid password", parsed.error.flatten());
  }
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }
  const updated = { ...user, mustChangePassword: false };
  db.users.set(updated.id, updated);
  return buildSession(db, updated.id);
}

// ─── Route registration (idempotent — guards against re-registration in tests) ──
let registered = false;
export function registerAuthRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("POST", "/api/v1/auth/login", loginHandler, { mutating: true });
  registerRoute("POST", "/api/v1/auth/logout", logoutHandler, { mutating: true });
  registerRoute("GET", "/api/v1/auth/session", sessionHandler);
  registerRoute("POST", "/api/v1/auth/password/change", changePasswordHandler, {
    mutating: true,
  });
}

registerAuthRoutes();

// ─── Public API surface ──────────────────────────────────────────────────────

interface RequestOptions {
  idempotencyKey?: string;
  correlationId?: string;
}

function buildHeaders(opts: RequestOptions = {}): Record<string, string> {
  const headers: Record<string, string> = {};
  if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;
  if (opts.correlationId) headers["X-Correlation-Id"] = opts.correlationId;
  return headers;
}

export interface LoginInput {
  username: string;
  password: string;
}

export async function login(input: LoginInput, opts?: RequestOptions): Promise<Session> {
  return dispatch(
    {
      method: "POST",
      path: "/api/v1/auth/login",
      body: input,
      headers: {
        ...buildHeaders(opts),
        "Idempotency-Key": opts?.idempotencyKey ?? newCorrelationId(),
      },
    },
    Session,
  );
}

export async function logout(opts?: RequestOptions): Promise<OkAck> {
  return dispatch(
    {
      method: "POST",
      path: "/api/v1/auth/logout",
      headers: {
        ...buildHeaders(opts),
        "Idempotency-Key": opts?.idempotencyKey ?? newCorrelationId(),
      },
    },
    OkAck,
  );
}

export async function session(opts?: RequestOptions): Promise<Session | null> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/auth/session",
      headers: buildHeaders(opts),
    },
    SessionOrNull,
  );
}

export interface CompletePasswordChangeInput {
  newPassword: string;
}

export async function completePasswordChange(
  input: CompletePasswordChangeInput,
  opts?: RequestOptions,
): Promise<Session> {
  return dispatch(
    {
      method: "POST",
      path: "/api/v1/auth/password/change",
      body: input,
      headers: {
        ...buildHeaders(opts),
        "Idempotency-Key": opts?.idempotencyKey ?? newCorrelationId(),
      },
    },
    Session,
  );
}
