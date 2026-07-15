/**
 * In-memory + localStorage persistence for the active session.
 *
 * Metadata (user, expiry) may be persisted for UX continuity across reloads.
 * The access token is held only in module memory — never written to storage —
 * so XSS cannot exfiltrate a durable bearer token from localStorage.
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

export function loadStoredSession(): Session | null {
  if (typeof window === "undefined") return sessionCache;

  try {
    const raw = readJson<Record<string, unknown>>(window.localStorage.getItem(SESSION_STORAGE_KEY));
    if (!raw) {
      return sessionCache;
    }
    const persisted = sanitizePersistedRaw(raw);
    if (!persisted) {
      return sessionCache;
    }
    // Never rehydrate an access token from storage — keep only the in-memory token if present.
    const memoryToken = sessionCache?.accessToken;
    sessionCache = {
      user: persisted.user,
      expiresAt: persisted.expiresAt,
      accessToken: memoryToken && memoryToken.length > 0 ? memoryToken : "",
    };
  } catch {
    // Storage may be unavailable; keep the in-memory copy.
  }
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
  const token = sessionCache?.accessToken;
  if (token && token.length > 0) return token;
  // Do not pull tokens from disk — only from the in-memory cache.
  return null;
}
