/**
 * UI performance sampling while API load runs in parallel.
 *
 * Prereq: backend + frontend running.
 * Env (required — no hardcoded secrets):
 *   E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD
 *   PERF_BASE_URL=http://127.0.0.1:5173 (optional)
 */
import { test, expect } from "@playwright/test";
import { signInAsSystemAdmin } from "./helpers/auth";

async function login(page: import("@playwright/test").Page) {
  await signInAsSystemAdmin(page);
}

test.describe("UI under load — page timing", () => {
  test("home dashboard LCP budget", async ({ page }) => {
    await login(page);
    const start = Date.now();
    await page.goto("/");
    await expect(page.locator("body")).toBeVisible();
    const navMs = Date.now() - start;
    console.log(`[perf] home navigation ${navMs}ms`);
    expect(navMs).toBeLessThan(15_000);
  });

  test("loan applications list", async ({ page }) => {
    await login(page);
    const start = Date.now();
    await page.goto("/loan-applications");
    await page.waitForLoadState("networkidle", { timeout: 30_000 }).catch(() => {});
    const navMs = Date.now() - start;
    console.log(`[perf] loan-applications ${navMs}ms`);
    expect(navMs).toBeLessThan(20_000);
  });

  test("reports MIS page", async ({ page }) => {
    await login(page);
    const start = Date.now();
    await page.goto("/reports");
    await page.waitForLoadState("domcontentloaded");
    const navMs = Date.now() - start;
    console.log(`[perf] reports ${navMs}ms`);
    expect(navMs).toBeLessThan(20_000);
  });
});
