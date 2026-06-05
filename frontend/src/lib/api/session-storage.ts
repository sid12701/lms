/**
 * In-memory + localStorage persistence for the active session.
 *
 * The session shape used across the app shell and auth flows.
 */
import type { Session } from "@/features/auth/session-types";

export const SESSION_STORAGE_KEY = "bhawana-lms-session";

let sessionCache: Session | null = null;

function readJson<T>(raw: string | null): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

export function loadStoredSession(): Session | null {
  if (typeof window === "undefined") return sessionCache;
  try {
    sessionCache = readJson<Session>(window.localStorage.getItem(SESSION_STORAGE_KEY));
  } catch {
    // Storage may be unavailable in some contexts; keep the in-memory copy.
  }
  return sessionCache;
}

export function saveStoredSession(session: Session): void {
  sessionCache = session;
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Ignore — degrades to in-memory only.
  }
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
  return loadStoredSession()?.accessToken ?? null;
}
