import type { AuthSession } from './lms-api'

const SESSION_STORAGE_KEY = 'lms.auth.session'
let sessionCache: AuthSession | null = null

function readJson<T>(raw: string | null): T | null {
  if (!raw) {
    return null
  }

  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

export function loadStoredSession() {
  if (typeof window === 'undefined') {
    return sessionCache
  }

  try {
    sessionCache = readJson<AuthSession>(window.localStorage.getItem(SESSION_STORAGE_KEY))
  } catch {
    // Keep the in-memory session when browser storage is unavailable.
  }

  return sessionCache
}

export function saveStoredSession(session: AuthSession) {
  sessionCache = session

  if (typeof window === 'undefined') {
    return
  }

  try {
    window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session))
  } catch {
    // Keep the in-memory session when browser storage is unavailable.
  }
}

export function clearStoredSession() {
  sessionCache = null

  if (typeof window === 'undefined') {
    return
  }

  try {
    window.localStorage.removeItem(SESSION_STORAGE_KEY)
  } catch {
    // Ignore cleanup errors in restricted contexts.
  }
}

export function getStoredAccessToken() {
  return loadStoredSession()?.accessToken ?? null
}
