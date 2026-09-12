import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  adoptRemoteIntent,
  advanceAuthGeneration,
  captureAuthIntent,
  clearCookieInFlightForTests,
  enqueueCookieOp,
  getAuthGeneration,
  isCookieExchangeBlocked,
  isStaleIntent,
  readCookieInFlightMarker,
  COOKIE_JAR_OWNER_STORAGE_KEY,
  resetAuthCoordinatorForTests,
  setCookieLockManagerForTests,
  subscribeAuthGeneration,
} from "@/features/auth/auth-coordinator";
import { createTestCookieLockManager } from "@/features/auth/test-cookie-lock";
import { ApiError } from "@/lib/api/http-client";

function installTestLock(): void {
  setCookieLockManagerForTests(createTestCookieLockManager());
}

describe("auth-coordinator generation + cookie ordering", () => {
  beforeEach(() => {
    resetAuthCoordinatorForTests({ clearStorage: true });
    installTestLock();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    resetAuthCoordinatorForTests({ clearStorage: true });
  });

  it("storage event recovers the equal-epoch winner overwritten by a losing writer", () => {
    const subscriber = vi.fn();
    const unsubscribe = subscribeAuthGeneration(subscriber);
    const winner = { generation: 7, intent: "zz-winner" };
    const loser = { generation: 7, intent: "aa-loser" };
    window.localStorage.setItem("bhawana-lms-auth-generation", JSON.stringify(loser));
    window.dispatchEvent(
      new StorageEvent("storage", {
        key: "bhawana-lms-auth-generation",
        newValue: JSON.stringify(winner),
      }),
    );
    expect(captureAuthIntent()).toEqual(winner);
    expect(JSON.parse(window.localStorage.getItem("bhawana-lms-auth-generation")!)).toEqual(winner);
    window.localStorage.setItem("bhawana-lms-auth-generation", JSON.stringify(loser));
    window.dispatchEvent(
      new StorageEvent("storage", {
        key: "bhawana-lms-auth-generation",
        newValue: JSON.stringify(loser),
      }),
    );
    expect(captureAuthIntent()).toEqual(winner);
    expect(JSON.parse(window.localStorage.getItem("bhawana-lms-auth-generation")!)).toEqual(winner);
    unsubscribe();
  });

  it("owns a monotonic generation across advances", () => {
    const start = getAuthGeneration();
    const first = advanceAuthGeneration("logout");
    const second = advanceAuthGeneration("login");
    expect(first).toBe(start + 1);
    expect(second).toBe(start + 2);
    expect(getAuthGeneration()).toBe(second);
  });

  it("fails closed without sending when cross-tab locking is unavailable", async () => {
    setCookieLockManagerForTests(null);
    // jsdom provides no navigator.locks: no per-tab fallback may run.
    const exchange = vi.fn(async () => "must-never-run");
    await expect(enqueueCookieOp("login", captureAuthIntent(), exchange)).rejects.toMatchObject({
      name: "AuthCookieBlockedError",
    });
    expect(exchange).not.toHaveBeenCalled();
    // Nothing was written; a later healthy tab is not poisoned by us.
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("fails closed on corrupt marker storage without authorizing an exchange", async () => {
    window.localStorage.setItem("bhawana-lms-auth-cookie-inflight", "{not-json");
    expect(isCookieExchangeBlocked()).toBe(true);
    const exchange = vi.fn(async () => "must-never-run");
    await expect(enqueueCookieOp("login", captureAuthIntent(), exchange)).rejects.toMatchObject({
      name: "AuthCookieBlockedError",
    });
    expect(exchange).not.toHaveBeenCalled();
    // Corrupt marker is preserved (not silently cleared) until clean-context recovery.
    expect(window.localStorage.getItem("bhawana-lms-auth-cookie-inflight")).toBe("{not-json");
    clearCookieInFlightForTests();
    await expect(
      enqueueCookieOp("login", captureAuthIntent(), async () => "b-token"),
    ).resolves.toBe("b-token");
  });

  it("queues B login behind an old logout exchange (old failure cannot erase newer intent)", async () => {
    const order: string[] = [];
    const oldLogout = enqueueCookieOp("logout", captureAuthIntent(), async () => {
      order.push("old-logout-start");
      await new Promise((resolve) => setTimeout(resolve, 10));
      order.push("old-logout-end");
      throw new ApiError("old logout definitive failure", 500, "", "SERVER_ERROR", null, true);
    }).catch((error: Error) => error.message);

    const bIntent = captureAuthIntent();
    const bLogin = enqueueCookieOp("login", bIntent, async () => {
      order.push("b-login");
      return "b-token";
    });

    const [oldResult, bResult] = await Promise.all([oldLogout, bLogin]);
    expect(oldResult).toBe("old logout definitive failure");
    expect(bResult).toBe("b-token");
    expect(order).toEqual(["old-logout-start", "old-logout-end", "b-login"]);
    // Definitive failure settles the owner's marker; the healthy queue stays usable.
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("settles only the owner's marker and never a foreign one", async () => {
    // Op 1 sets its marker and holds the exchange open; op 2 must queue
    // behind the lock, not run marker-less.
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const first = enqueueCookieOp("refresh", captureAuthIntent(), async () => {
      await gate;
      return "first";
    });
    const order: string[] = [];
    const second = enqueueCookieOp("refresh", captureAuthIntent(), async () => {
      const marker = readCookieInFlightMarker();
      order.push(marker ? `marker:${marker.kind}` : "no-marker");
      return "second";
    });
    await new Promise((resolve) => setTimeout(resolve, 10));
    release();
    await expect(first).resolves.toBe("first");
    await expect(second).resolves.toBe("second");
    // The second op ran with its own marker set inside the lock.
    expect(order).toEqual(["marker:refresh"]);
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("fails closed while an orphan marker remains (no timeout/reload clear)", async () => {
    await enqueueCookieOp("refresh", captureAuthIntent(), async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
      return "settled";
    });
    expect(isCookieExchangeBlocked()).toBe(false);

    // Simulate a peer that started an exchange and never settled (closed tab
    // with outstanding response). Marker now carries a unique owner.
    window.localStorage.setItem(
      "bhawana-lms-auth-cookie-inflight",
      JSON.stringify({
        generation: 999,
        kind: "refresh",
        owner: "dead-peer-owner",
        startedAt: new Date().toISOString(),
      }),
    );
    expect(isCookieExchangeBlocked()).toBe(true);

    const exchange = vi.fn(async () => "b-token");
    await expect(enqueueCookieOp("login", captureAuthIntent(), exchange)).rejects.toMatchObject({
      name: "AuthCookieBlockedError",
    });
    expect(exchange).not.toHaveBeenCalled();
    // Still blocked — reload must not clear it.
    expect(isCookieExchangeBlocked()).toBe(true);
    expect(readCookieInFlightMarker()?.generation).toBe(999);

    // Explicit clean-context recovery only (test seam simulates a fresh
    // browser context; production recovery is close-all-tabs + clear site data).
    clearCookieInFlightForTests();
    expect(isCookieExchangeBlocked()).toBe(false);
    await expect(
      enqueueCookieOp("login", captureAuthIntent(), async () => "b-token"),
    ).resolves.toBe("b-token");
  });

  it("leaves the marker blocked on no-response/abort, settles on HTTP error or post-response parse failure", async () => {
    await expect(
      enqueueCookieOp("refresh", captureAuthIntent(), async () => {
        throw new TypeError("Failed to fetch");
      }),
    ).rejects.toBeInstanceOf(TypeError);
    // Uncertain: cannot prove the older response will never Set-Cookie.
    expect(isCookieExchangeBlocked()).toBe(true);
    clearCookieInFlightForTests();

    await expect(
      enqueueCookieOp("refresh", captureAuthIntent(), async () => {
        throw new DOMException("Aborted", "AbortError");
      }),
    ).rejects.toBeInstanceOf(DOMException);
    expect(isCookieExchangeBlocked()).toBe(true);
    clearCookieInFlightForTests();

    // Bare status-0 without settlement evidence proves nothing.
    await expect(
      enqueueCookieOp("refresh", captureAuthIntent(), async () => {
        throw new ApiError("no evidence", 0, "", null);
      }),
    ).rejects.toMatchObject({ status: 0 });
    expect(isCookieExchangeBlocked()).toBe(true);
    clearCookieInFlightForTests();

    // Real HTTP error response: settled, queue stays usable.
    await expect(
      enqueueCookieOp("refresh", captureAuthIntent(), async () => {
        throw new ApiError("revoked", 401, "", "TOKEN_REVOKED", null, true);
      }),
    ).rejects.toMatchObject({ status: 401 });
    expect(isCookieExchangeBlocked()).toBe(false);

    // Local parse failure AFTER a received response: settled evidence keeps
    // the queue usable instead of a permanent orphan.
    const { markHttpSettled } = await import("@/lib/api/http-client");
    await expect(
      enqueueCookieOp("refresh", captureAuthIntent(), async () => {
        throw markHttpSettled(new SyntaxError("Unexpected token < in JSON"));
      }),
    ).rejects.toBeInstanceOf(SyntaxError);
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("converges simultaneous same-epoch intents deterministically with invalidation", () => {
    // Two tabs read epoch 5 concurrently and both advance to 6 with unique
    // writer tokens — a plain read-modify-write cannot serialize this.
    resetAuthCoordinatorForTests({ clearStorage: true });
    window.localStorage.setItem(
      "bhawana-lms-auth-generation",
      JSON.stringify({ generation: 5, intent: "tab-X" }),
    );
    const seen: Array<{ generation: number; intent: string }> = [];
    const unsub = subscribeAuthGeneration((state) => {
      seen.push({ ...state });
    });
    try {
      // Simulate tab A advancing from the shared read.
      const aGen = advanceAuthGeneration("tab-A-intent");
      expect(aGen).toBe(6);
      const aIntent = captureAuthIntent();

      // Tab B (stale read of the same epoch 5) announces its own epoch-6
      // intent with a fixed token for determinism.
      const bIntent = { generation: 6, intent: "intent-B-fixed-token" };
      const aWinsFirst = aIntent.intent > bIntent.intent;
      const adopted = adoptRemoteIntent(bIntent);
      expect(adopted).toBe(!aWinsFirst);

      // Exchange announcements both ways: both tabs converge on the same
      // (generation, intent) winner regardless of arrival order.
      const winner = aWinsFirst ? aIntent : bIntent;
      adoptRemoteIntent(aIntent);
      const current = captureAuthIntent();
      expect(current).toEqual(winner);

      // Any adopted foreign state notified listeners (invalidation signal).
      expect(seen.length).toBeGreaterThan(0);
      // A refresh captured for the losing intent is stale.
      const loser = aWinsFirst ? bIntent : aIntent;
      if (loser.intent !== winner.intent) {
        expect(isStaleIntent(loser)).toBe(true);
      }
      expect(isStaleIntent(winner)).toBe(false);
    } finally {
      unsub();
    }
  });

  it("rejects stale queued refresh/password before any HTTP dispatch", async () => {
    const staleRefresh = captureAuthIntent();
    const stalePassword = captureAuthIntent();
    advanceAuthGeneration("logout");
    const refreshExchange = vi.fn(async () => "must-never-run");
    const passwordExchange = vi.fn(async () => "must-never-run");

    await expect(enqueueCookieOp("refresh", staleRefresh, refreshExchange)).rejects.toMatchObject({
      name: "AuthStaleResultError",
    });
    await expect(
      enqueueCookieOp("password", stalePassword, passwordExchange),
    ).rejects.toMatchObject({ name: "AuthStaleResultError" });
    // Obsolete credential work never dispatched: the B cookie is untouched.
    expect(refreshExchange).not.toHaveBeenCalled();
    expect(passwordExchange).not.toHaveBeenCalled();
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("rejects a stale queued logout when a newer jar cookie is proven", async () => {
    const staleLogout = captureAuthIntent();
    advanceAuthGeneration("b-login");
    const bIntent = captureAuthIntent();
    await enqueueCookieOp("login", bIntent, async () => "b-token");

    const exchange = vi.fn(async () => "must-never-run");
    await expect(enqueueCookieOp("logout", staleLogout, exchange)).rejects.toMatchObject({
      name: "AuthStaleResultError",
    });
    expect(exchange).not.toHaveBeenCalled();
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("dispatches a stale queued logout with unknown jar ownership (healthy old-logout queue)", async () => {
    const staleLogout = captureAuthIntent();
    advanceAuthGeneration("b-login");
    // No jar-owner metadata: no proven newer cookie, so the old family
    // revocation still settles instead of leaking.
    const exchange = vi.fn(async () => "logout-settled");
    await expect(enqueueCookieOp("logout", staleLogout, exchange)).resolves.toBe("logout-settled");
    expect(exchange).toHaveBeenCalledTimes(1);
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("fails closed at the lock boundary on corrupt intent storage", async () => {
    const intent = captureAuthIntent();
    window.localStorage.setItem("bhawana-lms-auth-generation", "{corrupt");
    const exchange = vi.fn(async () => "must-never-run");
    await expect(enqueueCookieOp("refresh", intent, exchange)).rejects.toMatchObject({
      name: "AuthCookieBlockedError",
    });
    expect(exchange).not.toHaveBeenCalled();
  });

  it("records jar ownership inside the lock before a queued stale logout can dispatch", async () => {
    const staleLogout = captureAuthIntent();
    advanceAuthGeneration("b-login");
    const bIntent = captureAuthIntent();
    let releaseExchange!: () => void;
    const exchangeGate = new Promise<void>((resolve) => {
      releaseExchange = resolve;
    });
    const loginP = enqueueCookieOp("login", bIntent, async () => {
      await exchangeGate;
      return "b-token";
    });
    const staleLogoutP = enqueueCookieOp("logout", staleLogout, async () => "must-never-run");
    await new Promise((resolve) => setTimeout(resolve, 10));
    releaseExchange!();
    await expect(loginP).resolves.toBe("b-token");
    await expect(staleLogoutP).rejects.toMatchObject({ name: "AuthStaleResultError" });
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("records jar ownership on settled parse failure after Set-Cookie", async () => {
    const { markHttpSettled } = await import("@/lib/api/http-client");
    const intent = captureAuthIntent();
    await expect(
      enqueueCookieOp("login", intent, async () => {
        throw markHttpSettled(new SyntaxError("Unexpected token"));
      }),
    ).rejects.toBeInstanceOf(SyntaxError);
    expect(JSON.parse(window.localStorage.getItem(COOKIE_JAR_OWNER_STORAGE_KEY)!)).toMatchObject({
      generation: intent.generation,
      intent: intent.intent,
      cleared: false,
    });
    expect(isCookieExchangeBlocked()).toBe(false);
  });

  it("retains the in-flight marker when jar ownership persistence fails", async () => {
    const intent = captureAuthIntent();
    const originalSetItem = window.localStorage.setItem.bind(window.localStorage);
    vi.spyOn(window.localStorage, "setItem").mockImplementation((key, value) => {
      if (key === COOKIE_JAR_OWNER_STORAGE_KEY) {
        throw new DOMException("QuotaExceededError", "QuotaExceededError");
      }
      return originalSetItem(key, value);
    });
    await expect(enqueueCookieOp("login", intent, async () => "token")).rejects.toMatchObject({
      name: "AuthCookieBlockedError",
    });
    expect(isCookieExchangeBlocked()).toBe(true);
  });

  it("fails closed on corrupt jar owner metadata when evaluating stale logout", async () => {
    const staleLogout = captureAuthIntent();
    advanceAuthGeneration("b-login");
    window.localStorage.setItem(COOKIE_JAR_OWNER_STORAGE_KEY, "{corrupt");
    const exchange = vi.fn(async () => "must-never-run");
    await expect(enqueueCookieOp("logout", staleLogout, exchange)).rejects.toMatchObject({
      name: "AuthCookieBlockedError",
    });
    expect(exchange).not.toHaveBeenCalled();
  });

  it("rejects stale queued logout on equal-epoch tie-break when jar owner intent wins", async () => {
    adoptRemoteIntent({ generation: 5, intent: "seed" });
    advanceAuthGeneration("tab-a");
    const staleLogout = captureAuthIntent();
    expect(staleLogout.generation).toBe(6);
    const bIntent = { generation: 6, intent: `${staleLogout.intent}-zzzz-winner` };
    adoptRemoteIntent(bIntent);
    await enqueueCookieOp("login", captureAuthIntent(), async () => "b-token");
    const exchange = vi.fn(async () => "must-never-run");
    await expect(enqueueCookieOp("logout", staleLogout, exchange)).rejects.toMatchObject({
      name: "AuthStaleResultError",
    });
    expect(exchange).not.toHaveBeenCalled();
  });

  it("guards test-only seams outside dev builds", async () => {
    vi.resetModules();
    vi.stubEnv("DEV", false);
    vi.stubEnv("PROD", true);
    const isolated = await import("@/features/auth/auth-coordinator");
    try {
      expect(() => isolated.resetAuthCoordinatorForTests()).toThrow(/test-only seam/);
      expect(() => isolated.clearCookieInFlightForTests()).toThrow(/test-only seam/);
      expect(() => isolated.setCookieLockManagerForTests(createTestCookieLockManager())).toThrow(
        /test-only seam/,
      );
    } finally {
      vi.resetModules();
      vi.unstubAllEnvs();
    }
  });
});
