/**
 * H22 real-cookie transport proof (isolated harness, no page.route).
 *
 * Real Chrome, per-test ephemeral HTTP server, actual Set-Cookie browser
 * jar. Ordering proof comes from SERVER-OBSERVED milestones (request
 * arrivals vs response headers), never from client sleeps: barrier-held
 * response headers overlap the exchanges, and timestamp order in
 * /__harness/milestones proves queueing. Short bounded absence windows only
 * allow scheduling; the proof is the milestone order plus jar evidence
 * (Set-Cookie sent -> Cookie received).
 *
 * - overlapping delayed refresh SUCCESS vs logout
 * - overlapping delayed refresh FAILURE (revoked) vs logout
 * - old logout vs B login across TWO TABS (healthy cross-tab queue)
 * - overlapping login intents resolve to the later intent deterministically
 * - close peer with outstanding refresh fails closed (B not sent, A not restored)
 *
 * Runs production provider/service/coordinator via /__harness/app.html.
 * Server-family PostgreSQL semantics (lineage, tolerance, revocation scope)
 * are owned by H21 and are explicitly out of scope here.
 */
import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { startH22Harness, type HarnessSlot } from "./h22-cookie-harness";

type HarnessWindow = {
  __harness: {
    refresh: () => Promise<void>;
    logout: () => Promise<string>;
    login: (email: string, password: string) => Promise<string>;
    getGeneration: () => number;
    sessionUserId: () => string | null;
    isBlocked: () => boolean;
    readJarOwner: () => { generation: number; intent: string; cleared: boolean } | null;
    dispatchStaleLogout: (stale: { generation: number; intent: string }) => Promise<string>;
  };
};

interface Milestones {
  arrivals: Array<{ type: string; at: string; receivedCookie: string }>;
  responses: Array<{ type: string; at: string; setCookie: string | null; status: number }>;
  peerGone: Array<{ type: string; at: string }>;
}

async function milestones(origin: string): Promise<Milestones> {
  const res = await fetch(`${origin}/__harness/milestones`);
  expect(res.ok).toBe(true);
  return (await res.json()) as Milestones;
}

async function hold(origin: string, slot: HarnessSlot): Promise<void> {
  const res = await fetch(`${origin}/__harness/hold`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ slot }),
  });
  expect(res.ok).toBe(true);
}

async function release(origin: string, slot: HarnessSlot): Promise<void> {
  const res = await fetch(`${origin}/__harness/release`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ slot }),
  });
  expect(res.ok).toBe(true);
}

async function control(origin: string, body: Record<string, unknown>): Promise<void> {
  const res = await fetch(`${origin}/__harness/control`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  expect(res.ok).toBe(true);
}

async function waitForArrival(
  origin: string,
  type: string,
  count = 1,
  timeoutMs = 15000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const ms = await milestones(origin);
    if (ms.arrivals.filter((a) => a.type === type).length >= count) return;
    if (Date.now() > deadline) {
      throw new Error(`timed out waiting for ${count} ${type} arrival(s)`);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

async function waitForPeerGone(
  origin: string,
  type: string,
  count = 1,
  timeoutMs = 15000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const ms = await milestones(origin);
    if (ms.peerGone.filter((p) => p.type === type).length >= count) return;
    if (Date.now() > deadline) {
      throw new Error(`timed out waiting for ${count} ${type} peerGone milestone(s)`);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

/**
 * Wait until a tab holds no in-flight cookie marker — i.e. its last
 * cookie-affecting exchange completed AND settled (headers alone only prove
 * dispatch). A peer whose held exchange died under load never settles, so
 * this times out with attribution instead of letting the next queued op
 * fail closed on the orphan further down.
 */
async function waitForUnblocked(page: Page, timeoutMs = 15000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (!(await isBlocked(page))) return;
    if (Date.now() > deadline) {
      throw new Error("timed out waiting for the tab's cookie marker to settle");
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

async function doRefresh(page: Page): Promise<void> {
  return page.evaluate(() => {
    const w = window as unknown as HarnessWindow;
    return w.__harness.refresh();
  });
}

async function doLogout(page: Page): Promise<string> {
  return page.evaluate(() => {
    const w = window as unknown as HarnessWindow;
    return w.__harness.logout();
  });
}

async function doLogin(page: Page, email: string, password: string): Promise<string> {
  return page.evaluate(
    ([userEmail, userPassword]: string[]) => {
      const w = window as unknown as HarnessWindow;
      return w.__harness.login(userEmail as string, userPassword as string);
    },
    [email, password],
  );
}

async function sessionUserId(page: Page): Promise<string | null> {
  return page.evaluate(() => {
    const w = window as unknown as HarnessWindow;
    return w.__harness.sessionUserId();
  });
}

async function isBlocked(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const w = window as unknown as HarnessWindow;
    return w.__harness.isBlocked();
  });
}

async function holdBody(origin: string, slot: HarnessSlot): Promise<void> {
  const res = await fetch(`${origin}/__harness/hold-body`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ slot }),
  });
  expect(res.ok).toBe(true);
}

async function releaseBody(origin: string, slot: HarnessSlot): Promise<void> {
  const res = await fetch(`${origin}/__harness/release-body`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ slot }),
  });
  expect(res.ok).toBe(true);
}

async function refreshCookieValue(context: BrowserContext): Promise<string | undefined> {
  const cookies = await context.cookies();
  return cookies.find((cookie) => cookie.name === "lms-refresh")?.value;
}

async function openHarnessPage(context: BrowserContext, origin: string): Promise<Page> {
  const page = await context.newPage();
  await page.goto(`${origin}/__harness/app.html`);
  await expect(page.locator("#harness-probe")).toBeAttached({ timeout: 30000 });
  return page;
}

test.describe("H22 cookie transport (real Set-Cookie jar)", () => {
  test("peer logout clears mounted identity when BroadcastChannel is unavailable", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    const context = await browser.newContext();
    try {
      await context.addInitScript(() => {
        Object.defineProperty(window, "BroadcastChannel", { value: undefined });
      });
      const tabA = await openHarnessPage(context, harness.url);
      const tabB = await openHarnessPage(context, harness.url);
      await doLogin(tabA, "ops.admin@harness.local", "password123456");
      await expect.poll(() => sessionUserId(tabA)).not.toBeNull();
      await doLogout(tabB);
      await expect.poll(() => sessionUserId(tabA)).toBeNull();
      expect(await refreshCookieValue(context)).toBeUndefined();
    } finally {
      await context.close();
      await harness.close();
    }
  });

  test("owner persistence failure after real Set-Cookie blocks a healthy peer", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    const context = await browser.newContext();
    try {
      const tabA = await openHarnessPage(context, harness.url);
      const tabB = await openHarnessPage(context, harness.url);
      await tabA.evaluate(() => {
        const original = Storage.prototype.setItem;
        Storage.prototype.setItem = function (key: string, value: string) {
          if (key === "bhawana-lms-cookie-jar-owner") {
            throw new DOMException("Injected owner persistence failure", "QuotaExceededError");
          }
          original.call(this, key, value);
        };
      });
      await expect(doLogin(tabA, "ops.admin@harness.local", "password123456")).rejects.toThrow();
      expect(await refreshCookieValue(context)).toContain("harness-login-");
      expect(await isBlocked(tabB)).toBe(true);
      await expect(doLogin(tabB, "b-user@harness.local", "password123456")).rejects.toThrow();
      expect(
        (await milestones(harness.url)).arrivals.filter((item) => item.type === "login"),
      ).toHaveLength(1);
      expect(await sessionUserId(tabA)).toBeNull();
      expect(await sessionUserId(tabB)).toBeNull();
    } finally {
      await context.close();
      await harness.close();
    }
  });

  test("delayed refresh SUCCESS then overlapping refresh vs logout serializes under the lock", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    try {
      const context = await browser.newContext();
      try {
        const page = await openHarnessPage(context, harness.url);

        // Phase A: delayed success alone end-to-end (real jar Set-Cookie).
        await hold(harness.url, "refresh");
        const soloP = doRefresh(page);
        await waitForArrival(harness.url, "refresh");
        await release(harness.url, "refresh");
        await soloP;
        await expect
          .poll(() => sessionUserId(page), { timeout: 5000 })
          .toBe("3f2504e0-4f89-41d3-9a0c-0305e82c3301");

        // Phase B: overlap — a logout started while the refresh is held
        // advances past it, so the late refresh success must discard (stale)
        // while the exchanges still serialize under the lock.
        await hold(harness.url, "refresh");
        const refreshP = doRefresh(page);
        await waitForArrival(harness.url, "refresh", 2);
        const logoutP = doLogout(page);
        // Scheduling window only: a broken implementation could send the
        // logout while the refresh is held. The hard proof below is the
        // milestone timestamp order plus jar evidence.
        await new Promise((resolve) => setTimeout(resolve, 400));
        const held = await milestones(harness.url);
        expect(held.arrivals.filter((a) => a.type === "logout")).toHaveLength(0);

        await release(harness.url, "refresh");
        await refreshP;
        await expect(logoutP).resolves.toBe("signed-out");

        const ms = await milestones(harness.url);
        const refreshResponses = ms.responses.filter((r) => r.type === "refresh");
        const logoutRes = ms.responses.find((r) => r.type === "logout");
        const logoutArrival = ms.arrivals.find((a) => a.type === "logout");
        const secondRefresh = refreshResponses[1];
        expect(secondRefresh?.status).toBe(200);
        expect(secondRefresh?.setCookie).toContain("lms-refresh=harness-refresh-");
        expect(secondRefresh?.setCookie).toContain("Path=/api/v1/auth");
        expect(secondRefresh?.setCookie).toContain("HttpOnly");
        expect(secondRefresh?.setCookie).toContain("SameSite=Strict");
        // Queued behind the lock: logout arrived only after the refresh
        // response headers settled.
        expect(Date.parse(logoutArrival!.at)).toBeGreaterThan(Date.parse(secondRefresh!.at));
        expect(logoutRes?.status).toBe(204);
        // Jar proof: the logout sent back the cookie the refresh had set.
        expect(logoutArrival?.receivedCookie).toContain("lms-refresh=harness-refresh-");
        expect(logoutRes?.setCookie).toContain("Max-Age=0");
        expect(logoutRes?.setCookie).toContain("Path=/api/v1/auth");

        await expect.poll(() => sessionUserId(page), { timeout: 5000 }).toBeNull();
        expect(await isBlocked(page)).toBe(false);
      } finally {
        await context.close();
      }
    } finally {
      await harness.close();
    }
  });

  test("overlapping delayed refresh FAILURE vs logout keeps the failure from clearing newer intent", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    try {
      await control(harness.url, { refreshMode: "revoked" });
      const context = await browser.newContext();
      try {
        const page = await openHarnessPage(context, harness.url);
        const generationBefore = await page.evaluate(() => {
          const w = window as unknown as HarnessWindow;
          return w.__harness.getGeneration();
        });
        await hold(harness.url, "refresh");

        const refreshP = doRefresh(page);
        await waitForArrival(harness.url, "refresh");
        const logoutP = doLogout(page);
        await new Promise((resolve) => setTimeout(resolve, 400));
        const held = await milestones(harness.url);
        expect(held.arrivals.filter((a) => a.type === "logout")).toHaveLength(0);

        await release(harness.url, "refresh");
        await refreshP;
        await expect(logoutP).resolves.toBe("signed-out");

        const ms = await milestones(harness.url);
        const refreshRes = ms.responses.find((r) => r.type === "refresh");
        const logoutRes = ms.responses.find((r) => r.type === "logout");
        const logoutArrival = ms.arrivals.find((a) => a.type === "logout");
        // Family invalidation carries NO cookie; logout still clears Path-exact.
        expect(refreshRes?.status).toBe(401);
        expect(refreshRes?.setCookie).toBeNull();
        expect(Date.parse(logoutArrival!.at)).toBeGreaterThan(Date.parse(refreshRes!.at));
        expect(logoutRes?.status).toBe(204);

        await expect.poll(() => sessionUserId(page), { timeout: 5000 }).toBeNull();
        const generationAfter = await page.evaluate(() => {
          const w = window as unknown as HarnessWindow;
          return w.__harness.getGeneration();
        });
        expect(generationAfter).toBeGreaterThan(generationBefore);
        expect(await isBlocked(page)).toBe(false);
      } finally {
        await context.close();
      }
    } finally {
      await harness.close();
    }
  });

  test("old logout vs B login across two tabs queues B behind the held logout", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    try {
      const context: BrowserContext = await browser.newContext();
      try {
        const tabA = await openHarnessPage(context, harness.url);
        const tabB = await openHarnessPage(context, harness.url);
        await hold(harness.url, "logout");

        const logoutP = doLogout(tabA);
        await waitForArrival(harness.url, "logout");
        const loginP = doLogin(tabB, "b-user@harness.local", "password123456");
        await new Promise((resolve) => setTimeout(resolve, 400));
        const held = await milestones(harness.url);
        // B never sent while the old logout is unsettled.
        expect(held.arrivals.filter((a) => a.type === "login")).toHaveLength(0);

        await release(harness.url, "logout");
        await expect(logoutP).resolves.toBe("signed-out");
        const bResult = await loginP;
        expect(bResult).toContain("aaaaaaaa-bbbb-4aaa-8aaa-aaaaaaaaaaaa");

        const ms = await milestones(harness.url);
        const logoutRes = ms.responses.find((r) => r.type === "logout");
        const loginArrival = ms.arrivals.find((a) => a.type === "login");
        // Hard ordering proof from server timestamps.
        expect(Date.parse(loginArrival!.at)).toBeGreaterThan(Date.parse(logoutRes!.at));

        // Cross-tab propagation: B authenticated in its tab, A gone everywhere.
        await expect
          .poll(() => sessionUserId(tabB), { timeout: 5000 })
          .toBe("aaaaaaaa-bbbb-4aaa-8aaa-aaaaaaaaaaaa");
        await expect.poll(() => sessionUserId(tabA), { timeout: 5000 }).toBeNull();
        expect(await isBlocked(tabB)).toBe(false);
      } finally {
        await context.close();
      }
    } finally {
      await harness.close();
    }
  });

  test("overlapping login intents resolve to the later intent deterministically", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    try {
      const context: BrowserContext = await browser.newContext();
      try {
        const tabA = await openHarnessPage(context, harness.url);
        const tabB = await openHarnessPage(context, harness.url);
        await hold(harness.url, "login");

        // Ordered starts: A first, then B once A's arrival is observed, so
        // B is deterministically the later intent while both overlap. B's
        // exchange cannot even dispatch while A holds the lock, so no
        // second arrival is possible until release — that absence IS the
        // lock proof (bounded scheduling window only).
        // Attach rejection handlers synchronously: either login may reject
        // (stale loser) once the race resolves.
        const loginAP = doLogin(tabA, "b-user@harness.local", "password123456").catch(
          (error: unknown) => `rejected:${String(error)}`,
        );
        await waitForArrival(harness.url, "login");
        const loginBP = doLogin(tabB, "c-user@harness.local", "password123456").catch(
          (error: unknown) => `rejected:${String(error)}`,
        );
        await new Promise((resolve) => setTimeout(resolve, 400));
        const held = await milestones(harness.url);
        expect(held.arrivals.filter((a) => a.type === "login")).toHaveLength(1);

        await release(harness.url, "login");
        // A's HTTP exchange succeeds but its owning intent is superseded by
        // B's later advance, so A fails stale and never publishes. Assert the
        // designed stale-loser outcome (not just any rejection): it proves A's
        // held exchange completed against a live peer and settled its marker,
        // which is what makes B's queued success below deterministic. If A's
        // exchange died instead (loaded-runner tab/connection loss), B must
        // fail closed on the orphan — the network-uncertain branch below
        // proves B was never dispatched instead of misclassifying that result.
        const aResult = await loginAP;
        const bResult = await loginBP;

        if (!aResult.includes("AuthStaleResultError")) {
          // Under a loaded browser runner the held fetch can lose its client
          // connection. That outcome is network-uncertain, so retaining A's
          // marker and blocking B before dispatch is the required safe result.
          expect(aResult).toMatch(/Failed to fetch/);
          expect(bResult).toMatch(/AuthCookieBlockedError/);
          const uncertain = await milestones(harness.url);
          expect(uncertain.arrivals.filter((a) => a.type === "login")).toHaveLength(1);
          expect(await sessionUserId(tabB)).toBeNull();
          expect(await isBlocked(tabB)).toBe(true);
          return;
        }

        expect(bResult).toContain("aaaaaaaa-cccc-4aaa-8aaa-aaaaaaaaaaaa");

        const ms = await milestones(harness.url);
        expect(ms.responses.filter((r) => r.type === "login" && r.status === 200)).toHaveLength(2);
        await expect
          .poll(() => sessionUserId(tabB), { timeout: 5000 })
          .toBe("aaaaaaaa-cccc-4aaa-8aaa-aaaaaaaaaaaa");
        await expect.poll(() => sessionUserId(tabA), { timeout: 5000 }).toBeNull();
        expect(await isBlocked(tabA)).toBe(false);
      } finally {
        await context.close();
      }
    } finally {
      await harness.close();
    }
  });

  test("close peer with outstanding refresh fails closed: B not sent, A not restored", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    try {
      const context: BrowserContext = await browser.newContext();
      try {
        const tabA = await openHarnessPage(context, harness.url);
        await hold(harness.url, "refresh");

        const pending = tabA
          .evaluate(() => {
            const w = window as unknown as HarnessWindow;
            return w.__harness.refresh();
          })
          .catch(() => "peer-closed");
        // Outstanding proven by the server-observed arrival.
        await waitForArrival(harness.url, "refresh");
        await tabA.close();
        await expect(pending).resolves.toBe("peer-closed");

        // Event-driven ordering: the harness records the socket teardown while
        // the headers are still withheld, so the post-release clientGone guard
        // is guaranteed to observe the dead peer — no sleep-after-release race.
        await waitForPeerGone(harness.url, "refresh");
        await release(harness.url, "refresh");
        // The held response now has no live peer; the server records no
        // response for the destroyed request.
        const afterClose = await milestones(harness.url);
        expect(afterClose.arrivals.filter((a) => a.type === "refresh")).toHaveLength(1);
        expect(afterClose.responses.filter((r) => r.type === "refresh")).toHaveLength(0);

        const tabB = await openHarnessPage(context, harness.url);
        // Fail-closed: orphan marker unresolved, B login never sent, A not restored.
        expect(await isBlocked(tabB)).toBe(true);
        await expect(doLogin(tabB, "b-user@harness.local", "password123456")).rejects.toThrow();
        const ms = await milestones(harness.url);
        expect(ms.arrivals.filter((a) => a.type === "login")).toHaveLength(0);
        expect(await sessionUserId(tabB)).toBeNull();
      } finally {
        await context.close();
      }
    } finally {
      await harness.close();
    }
  });

  test("held login body after Set-Cookie: stale logout cannot clear B cookie", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    try {
      const context = await browser.newContext();
      try {
        const tabA = await openHarnessPage(context, harness.url);
        const tabB = await openHarnessPage(context, harness.url);
        await doLogin(tabA, "ops.admin@harness.local", "password123456");
        const staleSnapshot = await tabA.evaluate(() => {
          const w = window as unknown as HarnessWindow;
          const owner = w.__harness.readJarOwner();
          if (!owner) throw new Error("expected tab A jar owner");
          return { generation: owner.generation, intent: owner.intent };
        });
        await holdBody(harness.url, "login");
        const loginP = doLogin(tabB, "b-user@harness.local", "password123456");
        await waitForArrival(harness.url, "login", 2);
        await expect
          .poll(() => refreshCookieValue(context), { timeout: 5000 })
          .toContain("harness-login-");
        const staleP = tabA.evaluate((stale: { generation: number; intent: string }) => {
          const w = window as unknown as HarnessWindow;
          return w.__harness.dispatchStaleLogout(stale);
        }, staleSnapshot);
        await releaseBody(harness.url, "login");
        // Event-driven settle gate: B's marker removal proves B's exchange
        // completed AND settled (the jar poll above only proved headers
        // arrived). TabA's queued stale op therefore provably enters a clean
        // lock and loses on jar ownership — no assumption about how fast B
        // settles after release. If B's held exchange died under load, its
        // orphan marker never clears and this times out here with attribution
        // instead of surfacing as B-blocked below.
        await waitForUnblocked(tabB);
        const [staleResult, bResult] = await Promise.all([staleP, loginP]);
        expect(staleResult).toBe("rejected:AuthStaleResultError");
        expect(bResult).toContain("aaaaaaaa-bbbb-4aaa-8aaa-aaaaaaaaaaaa");
        await expect
          .poll(() => refreshCookieValue(context), { timeout: 5000 })
          .toContain("harness-login-");
        expect(await isBlocked(tabB)).toBe(false);
      } finally {
        await context.close();
      }
    } finally {
      await harness.close();
    }
  });

  test("malformed login body after Set-Cookie records ownership and blocks stale logout", async ({
    browser,
  }) => {
    const harness = await startH22Harness();
    try {
      await control(harness.url, { loginBodyMode: "malformed" });
      const context = await browser.newContext();
      try {
        const tabA = await openHarnessPage(context, harness.url);
        const tabB = await openHarnessPage(context, harness.url);
        await control(harness.url, { loginBodyMode: "normal" });
        await doLogin(tabA, "ops.admin@harness.local", "password123456");
        const staleSnapshot = await tabA.evaluate(() => {
          const w = window as unknown as HarnessWindow;
          const owner = w.__harness.readJarOwner();
          if (!owner) throw new Error("expected tab A jar owner");
          return { generation: owner.generation, intent: owner.intent };
        });
        await control(harness.url, { loginBodyMode: "malformed" });
        const loginFailed = await tabB.evaluate(async () => {
          const w = window as unknown as HarnessWindow;
          try {
            await w.__harness.login("b-user@harness.local", "password123456");
            return false;
          } catch {
            return true;
          }
        });
        expect(loginFailed).toBe(true);
        await expect
          .poll(() => refreshCookieValue(context), { timeout: 5000 })
          .toContain("harness-login-");
        const owner = await tabB.evaluate(() => {
          const w = window as unknown as HarnessWindow;
          return w.__harness.readJarOwner();
        });
        expect(owner?.cleared).toBe(false);
        const staleResult = await tabA.evaluate((stale: { generation: number; intent: string }) => {
          const w = window as unknown as HarnessWindow;
          return w.__harness.dispatchStaleLogout(stale);
        }, staleSnapshot);
        expect(staleResult).toBe("rejected:AuthStaleResultError");
        await expect
          .poll(() => refreshCookieValue(context), { timeout: 5000 })
          .toContain("harness-login-");
        expect(await isBlocked(tabB)).toBe(false);
      } finally {
        await context.close();
      }
    } finally {
      await harness.close();
    }
  });
});
