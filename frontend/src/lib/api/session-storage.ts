/**
 * In-memory + localStorage persistence for the active session.
 *
 * Metadata (user, expiry) may be persisted for UX continuity across reloads.
 * The access token is held only in module memory — never written to storage —
 * so XSS cannot exfiltrate a durable bearer token from localStorage.
 *
 * Identity consistency: the bearer is bound to the FULL effective
 * identity (user id + role + LSP scope), never to the id alone — a
 * role/LSP-only change with the same id must not pair the old bearer with
 * the new scope. Stale in-memory state is cleared on foreign removal/change
 * instead of being returned. Storage UNAVAILABLE (throws) fails closed
 * (memory cleared, null returned); storage ABSENT (no key) after a foreign
 * logout clears stale memory and returns null rather than resurrecting A.
 */
import type { Session } from "@/features/auth/session-types";
import { SessionUser } from "@/features/auth/session-types";

export const SESSION_STORAGE_KEY = "bhawana-lms-session";

type PersistedSession = {
  user: Session["user"];
  expiresAt: string;
};

let sessionCache: Session | null = null;

function readJson<T>(raw: string | null): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function persistMetadata(session: Pick<Session, "user" | "expiresAt">): void {
  if (typeof window === "undefined") return;
  const persisted: PersistedSession = {
    user: session.user,
    expiresAt: session.expiresAt,
  };
  try {
    window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(persisted));
  } catch {
    // Ignore — degrades to in-memory only.
  }
}

function sanitizePersistedRaw(raw: Record<string, unknown>): PersistedSession | null {
  if (!raw || typeof raw !== "object") return null;
  try {
    const user = SessionUser.parse(raw.user);
    const expiresAt = typeof raw.expiresAt === "string" ? raw.expiresAt : null;
    if (!expiresAt) return null;
    const persisted: PersistedSession = { user, expiresAt };
    // Drop any legacy accessToken (or other secrets) that may still be on disk.
    if ("accessToken" in raw) {
      persistMetadata(persisted);
    }
    return persisted;
  } catch {
    return null;
  }
}

function sameEffectiveIdentity(left: Session["user"], right: Session["user"]): boolean {
  return (
    left.id === right.id &&
    left.role === right.role &&
    (left.lspId ?? null) === (right.lspId ?? null)
  );
}

function readDiskRaw(): { status: "absent" | "present"; raw: string | null } {
  // Throws when ordering storage is unavailable — callers fail closed.
  if (typeof window === "undefined") return { status: "absent", raw: null };
  const raw = window.localStorage.getItem(SESSION_STORAGE_KEY);
  return raw ? { status: "present", raw } : { status: "absent", raw: null };
}

export function loadStoredSession(): Session | null {
  if (typeof window === "undefined") return sessionCache;

  let disk: { status: "absent" | "present"; raw: string | null };
  try {
    disk = readDiskRaw();
  } catch {
    // Storage unavailable: fail closed — never return a bearer that cannot
    // be validated against persisted metadata.
    sessionCache = null;
    return null;
  }
  if (disk.status === "absent") {
    // No persisted metadata. Memory without disk means a foreign change
    // (e.g. remote logout cleared the key) — clear stale memory instead of
    // resurrecting A after the remote logout.
    if (sessionCache) sessionCache = null;
    return null;
  }
  const persisted = sanitizePersistedRaw(readJson<Record<string, unknown>>(disk.raw) ?? {});
  if (!persisted) {
    // Present-but-invalid disk state: fail closed, drop stale memory.
    sessionCache = null;
    return null;
  }
  // Never cross-pair identities. The access token is bound to the full
  // in-memory effective identity only. If persisted metadata belongs to a
  // different identity (B on disk vs A in memory — id, role, or LSP
  // differs), or there is no in-memory bearer, return persisted metadata
  // with an EMPTY token so the caller takes the refresh/revalidation path —
  // never A's bearer under B's identity/scope.
  const memory = sessionCache;
  const memoryToken = memory?.accessToken;
  const sameIdentity =
    !!memory &&
    sameEffectiveIdentity(memory.user, persisted.user) &&
    typeof memoryToken === "string" &&
    memoryToken.length > 0;
  sessionCache = {
    user: persisted.user,
    expiresAt: persisted.expiresAt,
    accessToken: sameIdentity ? (memoryToken as string) : "",
  };
  return sessionCache;
}

export function saveStoredSession(session: Session): void {
  sessionCache = session;
  persistMetadata(session);
}

export function clearStoredSession(): void {
  sessionCache = null;
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // Ignore cleanup errors.
  }
}

export function getStoredAccessToken(): string | null {
  const memory = sessionCache;
  const token = memory?.accessToken;
  if (!token || token.length === 0) return null;
  // Do not bypass foreign invalidation while storage/broadcast processing is
  // pending: revalidate the in-memory bearer against persisted metadata on
  // every read. Any mismatch, absence, or unavailable storage clears stale
  // memory and yields no token (callers take the refresh path).
  try {
    if (typeof window === "undefined") return token;
    const disk = readDiskRaw();
    if (disk.status === "absent") {
      sessionCache = null;
      return null;
    }
    const persisted = sanitizePersistedRaw(readJson<Record<string, unknown>>(disk.raw) ?? {});
    if (!persisted || !sameEffectiveIdentity(memory.user, persisted.user)) {
      sessionCache = null;
      return null;
    }
    return token;
  } catch {
    sessionCache = null;
    return null;
  }
}
