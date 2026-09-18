/**
 * Auth coordinator — single owner of monotonic identity generation and
 * cross-tab cookie ordering.
 *
 * Packet: one coordinator below LoginPage/ChangePasswordPage/provider,
 * auth-api, HTTP-client and storage owns generation. Capture owning intent
 * before queue/async; every success/failure/bootstrap/login/password/logout/
 * replay path obeys it. Logout clears visible authenticated state
 * immediately. Pending B never sees A data/header/cache. Same-identity
 * renewal retains cache (no generation advance). Cross-tab announcements
 * carry generations only (never secrets) and invalidate foreign identity
 * immediately.
 *
 * Cookie ordering: a cross-tab Web Lock covers the actual complete
 * cookie-changing HTTP exchange. The lock is acquired FIRST; inside it the
 * pre-existing marker is validated as an orphan, a UNIQUE operation-owner
 * marker is set, the full exchange runs, and only the owner's own marker is
 * settled/removed — still inside the lock. A healthy peer waits for the
 * lock and queues; an orphan remaining after lock acquisition fails closed.
 * Fail closed BEFORE any cookie HTTP request if cross-tab locking is
 * unavailable or ordering storage is unavailable/corrupt. Never
 * timeout-clear, reload-clear, abort-clear, or use refresh/status
 * non-mutating recovery. Network uncertainty (no transport settlement
 * evidence) leaves the marker blocked; explicit recovery is close-all-tabs
 * + clean browser context/site state. Definitive HTTP settle (explicit
 * transport evidence from the actual response boundary) clears the owner's
 * marker and keeps the queue usable. Logical logout advances immediately;
 * its server cookie effect queues behind prior refresh; B login (its own
 * newer intent) queues after old logout. Failed old ops never erase newer
 * intent (callers gate storage writes on the captured intent).
 *
 * Generation is a Lamport epoch paired with a unique writer/intent token.
 * A plain localStorage read-modify-write is NOT claimed to be serialized:
 * simultaneous writers may choose the same epoch, so announcements carry
 * (generation, intent) and receivers converge deterministically — higher
 * epoch wins, and equal epochs break ties by higher intent token. Local
 * generations never decrease; any adopted foreign state invalidates
 * immediately.
 */

import { ApiError } from "@/lib/api/http-client";

export const AUTH_GENERATION_STORAGE_KEY = "bhawana-lms-auth-generation";
export const COOKIE_INFLIGHT_STORAGE_KEY = "bhawana-lms-auth-cookie-inflight";
export const COOKIE_JAR_OWNER_STORAGE_KEY = "bhawana-lms-cookie-jar-owner";
export const COOKIE_LOCK_NAME = "lms-auth-cookie";
export const GENERATION_BROADCAST_CHANNEL = "lms-auth-generation";
const COOKIE_MARKER_PROPAGATION_WAIT_MS = 1000;

export type CookieOpKind = "login" | "refresh" | "logout" | "password";

export interface CookieInFlightMarker {
  generation: number;
  kind: CookieOpKind;
  /** Unique operation owner; only the owner may settle/remove the marker. */
  owner: string;
  startedAt: string;
}

export interface GenerationAnnouncement {
  generation: number;
  intent: string;
  reason: string;
}

/** Lamport epoch + unique writer/intent identity. */
export interface AuthIntent {
  generation: number;
  intent: string;
}

export class AuthCookieBlockedError extends Error {
  readonly marker: CookieInFlightMarker | null;
  constructor(message: string, marker: CookieInFlightMarker | null) {
    super(message);
    this.name = "AuthCookieBlockedError";
    this.marker = marker;
  }
}

export class AuthStaleResultError extends Error {
  readonly generation: number;
  constructor(generation: number) {
    super("Stale auth result for a superseded identity generation.");
    this.name = "AuthStaleResultError";
    this.generation = generation;
  }
}

function readStoredIntentState(): AuthIntent | null {
  if (typeof window === "undefined") return null;
  let raw: string | null;
  try {
    raw = window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY);
  } catch {
    return null;
  }
  if (!raw) return { generation: 0, intent: "" };
  try {
    const parsed = JSON.parse(raw) as Partial<AuthIntent>;
    if (
      typeof parsed.generation !== "number" ||
      !Number.isFinite(parsed.generation) ||
      parsed.generation < 0 ||
      typeof parsed.intent !== "string"
    ) {
      return null;
    }
    return { generation: Math.floor(parsed.generation), intent: parsed.intent };
  } catch {
    return null;
  }
}

function persistIntentState(state: AuthIntent): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Best effort; in-memory state still advances.
  }
}

/**
 * Strict intent-store read for the lock boundary. Absent storage reads as
 * the zero state (fresh context). Present-but-corrupt or unavailable
 * storage throws AuthCookieBlockedError — corruption of the coordination
 * store is never treated as safe.
 */
function loadPersistedIntentForLock(): AuthIntent {
  if (typeof window === "undefined") {
    throw new AuthCookieBlockedError(
      "Cookie ordering storage is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
  let raw: string | null;
  try {
    raw = window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY);
  } catch {
    throw new AuthCookieBlockedError(
      "Cookie ordering storage is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
  if (!raw) return { generation: 0, intent: "" };
  try {
    const parsed = JSON.parse(raw) as Partial<AuthIntent>;
    if (
      typeof parsed.generation !== "number" ||
      !Number.isFinite(parsed.generation) ||
      parsed.generation < 0 ||
      typeof parsed.intent !== "string"
    ) {
      throw new Error("corrupt intent state");
    }
    return { generation: Math.floor(parsed.generation), intent: parsed.intent };
  } catch (error) {
    if (error instanceof AuthCookieBlockedError) throw error;
    throw new AuthCookieBlockedError(
      "Identity coordination storage is corrupt. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
}

/**
 * Which owning intent last set (or cleared) the browser cookie jar, as
 * mirrored in storage. Non-secret metadata: generations and opaque tokens
 * only, never credentials. Used solely for the stale-logout dispatch
 * policy below.
 */
export interface CookieJarOwner {
  generation: number;
  intent: string;
  cleared: boolean;
}

/**
 * Persist jar ownership inside the held lock. Throws on unavailable storage
 * so a cookie effect never unlocks without durable ownership metadata.
 */
function persistCookieJarOwner(intent: AuthIntent, cleared: boolean): void {
  if (typeof window === "undefined") {
    throw new AuthCookieBlockedError(
      "Cookie jar ownership metadata is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      readCookieInFlightMarker(),
    );
  }
  const owner: CookieJarOwner = {
    generation: intent.generation,
    intent: intent.intent,
    cleared,
  };
  try {
    window.localStorage.setItem(COOKIE_JAR_OWNER_STORAGE_KEY, JSON.stringify(owner));
  } catch {
    throw new AuthCookieBlockedError(
      "Cookie jar ownership metadata is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      readCookieInFlightMarker(),
    );
  }
}

/**
 * Strict jar-owner read for stale-logout policy. Absent key is a genuinely
 * clean context (no proven newer cookie). Present-but-corrupt or unavailable
 * storage throws — never treated as permission to dispatch a destructive
 * stale logout.
 */
function loadCookieJarOwnerForPolicy(): CookieJarOwner | null {
  if (typeof window === "undefined") {
    throw new AuthCookieBlockedError(
      "Cookie jar ownership metadata is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
  let raw: string | null;
  try {
    raw = window.localStorage.getItem(COOKIE_JAR_OWNER_STORAGE_KEY);
  } catch {
    throw new AuthCookieBlockedError(
      "Cookie jar ownership metadata is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<CookieJarOwner>;
    if (
      typeof parsed.generation !== "number" ||
      !Number.isFinite(parsed.generation) ||
      typeof parsed.intent !== "string" ||
      typeof parsed.cleared !== "boolean"
    ) {
      throw new Error("corrupt jar owner");
    }
    return {
      generation: Math.floor(parsed.generation),
      intent: parsed.intent,
      cleared: parsed.cleared,
    };
  } catch (error) {
    if (error instanceof AuthCookieBlockedError) throw error;
    throw new AuthCookieBlockedError(
      "Cookie jar ownership metadata is corrupt. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
}

/** Cookie-effect contract for a successful exchange (parsed body returned). */
function cookieJarEffectOnSuccess(kind: CookieOpKind): { record: true; cleared: boolean } | null {
  switch (kind) {
    case "login":
    case "refresh":
    case "password":
      return { record: true, cleared: false };
    case "logout":
      return { record: true, cleared: true };
    default:
      return null;
  }
}

/**
 * Cookie-effect contract for a definitive settled HTTP outcome that did not
 * return a parsed body. Parse failures after a received 2xx still issued
 * Set-Cookie; TOKEN_ROTATED and other 401/403 responses do not.
 */
function cookieJarEffectOnSettledError(
  kind: CookieOpKind,
  error: unknown,
): { record: true; cleared: boolean } | null {
  if (kind === "logout") {
    return null;
  }
  if (error instanceof ApiError && error.settled) {
    if (error.status === 401 && error.code === "TOKEN_ROTATED") return null;
    if (error.status >= 400) return null;
  }
  if (isHttpSettledEvidence(error)) {
    return { record: true, cleared: false };
  }
  return null;
}

function recordCookieJarEffectForOutcome(
  kind: CookieOpKind,
  intent: AuthIntent,
  error: unknown | null,
): void {
  const effect =
    error === null ? cookieJarEffectOnSuccess(kind) : cookieJarEffectOnSettledError(kind, error);
  if (effect?.record) {
    persistCookieJarOwner(intent, effect.cleared);
  }
}

function settleOwnedMarker(markerOwner: string): void {
  const current = readCookieInFlightMarker();
  if (current && current.owner === markerOwner) {
    removeCookieInFlightMarker();
  }
}

function newIntentToken(): string {
  try {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
  } catch {
    // Fall through to the counter fallback.
  }
  intentTokenCounter += 1;
  return `intent-${Date.now().toString(36)}-${intentTokenCounter.toString(36)}-${Math.floor(
    Math.random() * 0xffffffff,
  ).toString(36)}`;
}

let intentTokenCounter = 0;

let localIntentState: AuthIntent = readStoredIntentState() ?? { generation: 0, intent: "" };
const generationListeners = new Set<(state: AuthIntent) => void>();
let broadcast: BroadcastChannel | null = null;
let storageListenerInstalled = false;

function ensureStorageListener(): void {
  if (typeof window === "undefined" || storageListenerInstalled) return;
  window.addEventListener("storage", (event) => {
    if (event.key !== AUTH_GENERATION_STORAGE_KEY) return;
    // Retain both candidates: concurrent writers can choose the same epoch
    // and a lower intent can overwrite the durable key before the higher
    // event is delivered. Discarding that payload would lose the winner.
    const stored = readStoredIntentState();
    try {
      if (event.newValue) {
        const announced = JSON.parse(event.newValue) as Partial<AuthIntent>;
        if (typeof announced.generation === "number" && typeof announced.intent === "string") {
          adoptRemoteIntent({ generation: announced.generation, intent: announced.intent });
        }
      }
    } catch {
      // Malformed announcements cannot authorize any operation. Lock-time
      // storage validation remains strict and fails closed on corrupt data.
    }
    if (stored) adoptRemoteIntent(stored);
    // Repair an overwritten lower candidate even when this tab already knew
    // the winning intent and adoptRemoteIntent therefore made no change.
    if (stored && isRemoteWinner(localIntentState, stored)) persistIntentState(localIntentState);
  });
  storageListenerInstalled = true;
}

function ensureBroadcast(): BroadcastChannel | null {
  ensureStorageListener();
  if (typeof window === "undefined") return null;
  if (typeof window.BroadcastChannel === "undefined") return null;
  if (!broadcast) {
    try {
      broadcast = new window.BroadcastChannel(GENERATION_BROADCAST_CHANNEL);
      broadcast.onmessage = (event: MessageEvent) => {
        const data = event.data as Partial<GenerationAnnouncement> | null;
        if (!data || typeof data.generation !== "number" || typeof data.intent !== "string") return;
        adoptRemoteIntent({ generation: data.generation, intent: data.intent });
      };
    } catch {
      broadcast = null;
    }
  }
  return broadcast;
}

function notifyListeners(state: AuthIntent): void {
  const snapshot: AuthIntent = { generation: state.generation, intent: state.intent };
  for (const listener of generationListeners) {
    try {
      listener(snapshot);
    } catch {
      // Listener errors must not break the coordinator.
    }
  }
}

/** Current monotonic identity generation (epoch of the Lamport pair). */
export function getAuthGeneration(): number {
  return localIntentState.generation;
}

/** Current full intent identity; capture before queue/async work. */
export function captureAuthIntent(): AuthIntent {
  return { generation: localIntentState.generation, intent: localIntentState.intent };
}

/** True when the given generation is still the live identity epoch. */
export function isCurrentGeneration(generation: number): boolean {
  return generation === localIntentState.generation;
}

/** True when the given generation has been superseded. */
export function isStaleGeneration(generation: number): boolean {
  return generation !== localIntentState.generation;
}

/** True when a captured owning intent is no longer current (epoch or writer changed). */
export function isStaleIntent(captured: AuthIntent): boolean {
  return (
    captured.generation !== localIntentState.generation ||
    captured.intent !== localIntentState.intent
  );
}

/** Deterministic winner ordering: higher epoch wins; equal epochs break ties by higher intent token. */
function isRemoteWinner(remote: AuthIntent, local: AuthIntent): boolean {
  if (remote.generation !== local.generation) return remote.generation > local.generation;
  if (remote.intent === local.intent) return false;
  return remote.intent > local.intent;
}

/**
 * Advance to a new identity generation (logout, login-start, password-start,
 * signed-out). Lamport epoch: max(local, stored) + 1 with a fresh unique
 * writer token. Persists + broadcasts (generation, intent) only — never
 * secrets. Returns the new epoch.
 */
export function advanceAuthGeneration(reason: string): number {
  const stored = readStoredIntentState();
  const base = Math.max(localIntentState.generation, stored ? stored.generation : 0);
  localIntentState = { generation: base + 1, intent: newIntentToken() };
  persistIntentState(localIntentState);
  notifyListeners(localIntentState);
  const channel = ensureBroadcast();
  if (channel) {
    try {
      const announcement: GenerationAnnouncement = {
        generation: localIntentState.generation,
        intent: localIntentState.intent,
        reason,
      };
      channel.postMessage(announcement);
    } catch {
      // Broadcast is best effort; in-tab state already advanced.
    }
  }
  return localIntentState.generation;
}

/**
 * Adopt a remote (generation, intent); foreign state invalidates immediately.
 * Returns true when the remote state won and was adopted.
 */
export function adoptRemoteIntent(remote: AuthIntent): boolean {
  if (!Number.isFinite(remote.generation) || typeof remote.intent !== "string") return false;
  const candidate: AuthIntent = {
    generation: Math.floor(remote.generation),
    intent: remote.intent,
  };
  if (!isRemoteWinner(candidate, localIntentState)) return false;
  localIntentState = candidate;
  persistIntentState(localIntentState);
  notifyListeners(localIntentState);
  return true;
}

/** Adopt a larger remote generation (intent unknown — loses equal-epoch ties by design). */
export function adoptRemoteGeneration(remote: number): number {
  if (!Number.isFinite(remote)) return localIntentState.generation;
  adoptRemoteIntent({ generation: Math.floor(remote), intent: "" });
  return localIntentState.generation;
}

export function subscribeAuthGeneration(listener: (state: AuthIntent) => void): () => void {
  ensureBroadcast();
  generationListeners.add(listener);
  return () => {
    generationListeners.delete(listener);
  };
}

/**
 * Read the in-flight marker.
 *
 * Returns null ONLY when no marker key is present. Unavailable storage or a
 * present-but-corrupt marker throws AuthCookieBlockedError — a corrupt
 * marker must never read as "no marker" and silently authorize an exchange.
 */
export function readCookieInFlightMarker(): CookieInFlightMarker | null {
  if (typeof window === "undefined") return null;
  let raw: string | null;
  try {
    raw = window.localStorage.getItem(COOKIE_INFLIGHT_STORAGE_KEY);
  } catch {
    throw new AuthCookieBlockedError(
      "Cookie ordering storage is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<CookieInFlightMarker>;
    if (
      typeof parsed.generation !== "number" ||
      !Number.isFinite(parsed.generation) ||
      typeof parsed.kind !== "string" ||
      typeof parsed.owner !== "string" ||
      parsed.owner.length === 0 ||
      typeof parsed.startedAt !== "string"
    ) {
      throw new Error("corrupt marker shape");
    }
    return {
      generation: Math.floor(parsed.generation),
      kind: parsed.kind as CookieOpKind,
      owner: parsed.owner,
      startedAt: parsed.startedAt,
    };
  } catch (error) {
    if (error instanceof AuthCookieBlockedError) throw error;
    throw new AuthCookieBlockedError(
      "Cookie ordering marker is corrupt. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
}

function writeCookieInFlightMarker(marker: CookieInFlightMarker): void {
  if (typeof window === "undefined") {
    throw new AuthCookieBlockedError(
      "Cookie ordering storage is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
  try {
    window.localStorage.setItem(COOKIE_INFLIGHT_STORAGE_KEY, JSON.stringify(marker));
  } catch {
    throw new AuthCookieBlockedError(
      "Cookie ordering storage is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
}

function removeCookieInFlightMarker(): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(COOKIE_INFLIGHT_STORAGE_KEY);
  } catch {
    // Best effort.
  }
}

/**
 * A Web Lock handoff can beat cross-renderer localStorage propagation: the
 * next lock owner may briefly read the marker that the previous owner
 * removed before releasing the lock. Wait only for that removal event, then
 * re-read. The timeout never clears or ignores a marker; a real orphan still
 * fails closed below.
 */
async function readCookieInFlightMarkerAfterLockHandoff(): Promise<CookieInFlightMarker | null> {
  const initial = readCookieInFlightMarker();
  if (!initial || typeof window === "undefined") return initial;

  return new Promise<CookieInFlightMarker | null>((resolve, reject) => {
    let finished = false;

    const cleanup = (): void => {
      window.removeEventListener("storage", onStorage);
      window.clearTimeout(timeoutId);
    };
    const finish = (marker: CookieInFlightMarker | null): void => {
      if (finished) return;
      finished = true;
      cleanup();
      resolve(marker);
    };
    const readCurrent = (): CookieInFlightMarker | null | undefined => {
      try {
        return readCookieInFlightMarker();
      } catch (error) {
        if (!finished) {
          finished = true;
          cleanup();
          reject(error);
        }
        return undefined;
      }
    };
    const onStorage = (event: StorageEvent): void => {
      if (event.key !== COOKIE_INFLIGHT_STORAGE_KEY) return;
      const current = readCurrent();
      if (current === null) finish(null);
    };

    const timeoutId = window.setTimeout(() => {
      const finalMarker = readCurrent();
      if (finalMarker !== undefined) finish(finalMarker);
    }, COOKIE_MARKER_PROPAGATION_WAIT_MS);

    window.addEventListener("storage", onStorage);

    // Close the listener-registration race: removal may have propagated
    // between the first read and addEventListener.
    const current = readCurrent();
    if (current === null || current === undefined) {
      if (current === null) finish(null);
      return;
    }
  });
}

/** True when a persistent orphan marker blocks new cookie-affecting ops. */
export function isCookieExchangeBlocked(): boolean {
  try {
    return readCookieInFlightMarker() !== null;
  } catch (error) {
    if (error instanceof AuthCookieBlockedError) return true;
    throw error;
  }
}

/**
 * TEST-ONLY marker clear, available in test/dev builds only. Production
 * recovery is close-all-tabs + clean browser context/site state — an active
 * tab must never call a clearing helper to free the next login. Tests use
 * this to simulate a fresh browser context between isolated cases.
 */
export function clearCookieInFlightForTests(): void {
  assertTestSeamAvailable("clearCookieInFlightForTests");
  removeCookieInFlightMarker();
}

/** Test seams below are unavailable outside test/dev builds: production must
 * never reset the queue, inject locks, or clear the orphan marker. */
function assertTestSeamAvailable(name: string): void {
  if (!import.meta.env.DEV) {
    throw new Error(`${name} is a test-only seam and is unavailable in production.`);
  }
}

/** Cross-tab lock manager surface (Web Locks API shape, subset). */
export interface CookieLockManager {
  request<T>(name: string, fn: () => Promise<T>): Promise<T>;
}

let testLockManager: CookieLockManager | null = null;

/**
 * TEST-ONLY lock injection, available in test/dev builds only. Unit tests
 * run where real Web Locks are unavailable and provide a FIFO mutex fake;
 * real browser tests use actual Web Locks. Production with no locks fails
 * closed (never a per-tab fallback).
 */
export function setCookieLockManagerForTests(manager: CookieLockManager | null): void {
  assertTestSeamAvailable("setCookieLockManagerForTests");
  testLockManager = manager;
}

function supportsWebLocks(): boolean {
  return (
    typeof navigator !== "undefined" &&
    typeof (navigator as Navigator & { locks?: unknown }).locks !== "undefined" &&
    typeof (navigator as Navigator & { locks: { request: unknown } }).locks.request === "function"
  );
}

function getLockManager(): CookieLockManager | null {
  if (testLockManager) return testLockManager;
  if (!supportsWebLocks()) return null;
  const locks = (navigator as Navigator & { locks: LockManager }).locks;
  return {
    request: <T>(name: string, fn: () => Promise<T>): Promise<T> =>
      locks.request(name, () => fn()) as Promise<T>,
  };
}

// Serial in-tab queue for cookie-affecting exchanges. Logical invalidations
// advance immediately; their network effects (and B logins) serialize here
// and additionally under the cross-tab lock.
let cookieChain: Promise<void> = Promise.resolve();

/**
 * Enqueue a cookie-affecting HTTP exchange for a captured owning intent.
 *
 * The intent MUST be captured (advance + captureAuthIntent) BEFORE calling
 * enqueue — never rebased at run time: stale refresh/password work queued
 * for A must not adopt a newer B identity. A healthy queued B login is its
 * own newer intent captured after the old logout's synchronous advance.
 *
 * - Fails closed with AuthCookieBlockedError BEFORE any cookie HTTP request
 *   when cross-tab locking is unavailable, ordering storage is
 *   unavailable/corrupt, or an unresolved peer marker remains (B never sent).
 * - Reconciles the latest persisted intent inside the lock and rejects
 *   obsolete refresh/password/login work before marker/HTTP; a stale queued
 *   logout dispatches only when no newer jar cookie is proven (else stale).
 * - Acquires the cross-tab Web Lock FIRST; inside it, validates any
 *   pre-existing marker as an orphan (fail closed), sets a UNIQUE
 *   operation-owner marker, executes the FULL cookie exchange, and settles
 *   or removes ONLY the owner's own marker — still inside the lock. A
 *   healthy peer waits for the lock and queues.
 * - Definitive HTTP settle (explicit transport evidence from the actual
 *   response boundary) removes the owner's marker and keeps the queue
 *   usable. Network uncertainty (no settlement evidence) leaves the marker
 *   blocked until explicit clean-context recovery.
 * - Never timeout-clears, reload-clears, abort-clears, or probes
 *   refresh/status as recovery.
 */
export function enqueueCookieOp<T>(
  kind: CookieOpKind,
  intent: AuthIntent,
  exchange: (intent: AuthIntent) => Promise<T>,
): Promise<T> {
  const task: Promise<T> = cookieChain.then(() => runCookieExchange(kind, intent, exchange));
  // Keep the chain usable after success AND definitive failure; blocked or
  // uncertain states are represented by the persistent marker, not a broken
  // chain. A rejected task must not prevent later queued intents from running
  // their blocked check.
  cookieChain = task.then(
    () => undefined,
    () => undefined,
  );
  return task;
}

/**
 * True when the mirrored jar metadata proves a NEWER intent already set the
 * browser cookie: a stale queued logout must not dispatch and blindly clear
 * it. Absent owner metadata on a genuinely clean context means no proven
 * newer cookie. Corrupt or unavailable owner storage throws — never read as
 * permission to dispatch. Every coordinator cookie effect records ownership
 * before the lock is released.
 */
function isJarOwnedByNewerIntent(intent: AuthIntent): boolean {
  const owner = loadCookieJarOwnerForPolicy();
  if (!owner || owner.cleared) return false;
  if (owner.generation !== intent.generation) return owner.generation > intent.generation;
  return owner.intent !== intent.intent && owner.intent > intent.intent;
}

async function runCookieExchange<T>(
  kind: CookieOpKind,
  intent: AuthIntent,
  exchange: (intent: AuthIntent) => Promise<T>,
): Promise<T> {
  const lock = getLockManager();
  if (!lock) {
    // Explicitly banned per-tab fallback: no lock means ordering cannot be
    // proven, so fail closed before any cookie-affecting request is sent.
    throw new AuthCookieBlockedError(
      "Cross-tab cookie locking is unavailable. Close all app tabs and establish a clean browser context/site state before retrying.",
      null,
    );
  }
  return lock.request(COOKIE_LOCK_NAME, async () => {
    // INSIDE the lock, first reconcile with the latest PERSISTED intent:
    // another tab may have advanced while this op queued. Strict validation
    // — corrupt/unavailable coordination storage fails closed here.
    const persisted = loadPersistedIntentForLock();
    adoptRemoteIntent(persisted);

    // Reject obsolete work BEFORE marker/HTTP: a queued stale A
    // refresh/password/login must never dispatch its request and mutate the
    // current B cookie, even though its result would be discarded.
    if (isStaleIntent(intent)) {
      if (kind !== "logout" || isJarOwnedByNewerIntent(intent)) {
        throw new AuthStaleResultError(intent.generation);
      }
      // Stale queued logout with no proven newer jar cookie: dispatch to
      // settle the old family revocation (healthy old-logout -> B-login
      // queue). A newer jar cookie (recorded by B's success) rejects above.
    }

    // A pre-existing marker belongs to a peer that did not settle (closed
    // tab with outstanding response). It is an orphan — fail closed; B is
    // never sent. (Unavailable/corrupt storage also throws.)
    const orphan = await readCookieInFlightMarkerAfterLockHandoff();
    if (orphan) {
      throw new AuthCookieBlockedError(
        "A prior cookie-affecting exchange is unsettled. Close all app tabs and establish a clean browser context/site state before retrying.",
        orphan,
      );
    }

    const owner = newIntentToken();
    const marker: CookieInFlightMarker = {
      generation: intent.generation,
      kind,
      owner,
      startedAt: new Date().toISOString(),
    };
    writeCookieInFlightMarker(marker);

    try {
      const result = await exchange(intent);
      // Record jar ownership from the cookie-effect contract, then settle
      // ONLY our own marker — still inside the lock, before release.
      recordCookieJarEffectForOutcome(kind, intent, null);
      settleOwnedMarker(owner);
      return result;
    } catch (error) {
      // Persistence/ordering failures must retain the in-flight marker.
      if (error instanceof AuthCookieBlockedError) {
        throw error;
      }
      if (isDefinitiveHttpOutcome(error)) {
        // Definitive HTTP outcome: record any cookie effect from the received
        // response boundary, then settle so the next queued intent can run.
        try {
          recordCookieJarEffectForOutcome(kind, intent, error);
          settleOwnedMarker(owner);
        } catch (persistError) {
          // Ownership persistence failed — marker stays blocked (fail closed).
          if (persistError instanceof AuthCookieBlockedError) throw persistError;
          throw error;
        }
      }
      // Network uncertainty (no settlement evidence): LEAVE the marker
      // blocked. We cannot prove the older response will never Set-Cookie,
      // so B must not overtake it.
      throw error;
    }
  });
}

/**
 * Definitive = explicit transport settlement evidence from the actual
 * response boundary (see http-client markHttpSettled/isHttpSettled), i.e.
 * the server responded — even 4xx/5xx. Blocked/stale control errors are
 * marker-safe by owner matching. Anything else (TypeError, abort,
 * status-0, unmarked errors) is network-uncertain and retains the marker.
 */
function isDefinitiveHttpOutcome(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const name = (error as { name?: unknown }).name;
  if (name === "AuthCookieBlockedError" || name === "AuthStaleResultError") return true;
  if (name === "SessionRestoreError") {
    const cause = (error as { cause?: unknown }).cause;
    return isDefinitiveHttpOutcome(cause);
  }
  return isHttpSettledEvidence(error);
}

function isHttpSettledEvidence(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const record = error as Record<string | symbol, unknown> & { cause?: unknown };
  if (record[Symbol.for("bhawana.lms.httpSettled")] === true) return true;
  const status = (error as { status?: unknown }).status;
  const settled = (error as { settled?: unknown }).settled;
  // An ApiError with settled=true was built from a received response.
  // A bare numeric status (including 0) WITHOUT settled evidence proves
  // nothing and stays network-uncertain.
  if (settled === true && typeof status === "number") return true;
  if (record.cause !== undefined) return isDefinitiveHttpOutcome(record.cause);
  return false;
}

/** Test seam, test/dev builds only: reset in-memory chain, intent state, listeners, lock injection (not storage unless asked). */
export function resetAuthCoordinatorForTests(options: { clearStorage?: boolean } = {}): void {
  assertTestSeamAvailable("resetAuthCoordinatorForTests");
  cookieChain = Promise.resolve();
  generationListeners.clear();
  testLockManager = null;
  localIntentState = { generation: 0, intent: "" };
  persistIntentState(localIntentState);
  if (options.clearStorage && typeof window !== "undefined") {
    try {
      window.localStorage.removeItem(COOKIE_INFLIGHT_STORAGE_KEY);
      window.localStorage.removeItem(AUTH_GENERATION_STORAGE_KEY);
      window.localStorage.removeItem(COOKIE_JAR_OWNER_STORAGE_KEY);
    } catch {
      // Best effort.
    }
  }
}

/** Test seam, test/dev builds only: adopt an exact generation (drives stale-path tests). */
export function setAuthGenerationForTests(generation: number): void {
  assertTestSeamAvailable("setAuthGenerationForTests");
  localIntentState = { generation: Math.floor(generation), intent: "" };
  persistIntentState(localIntentState);
  notifyListeners(localIntentState);
}
