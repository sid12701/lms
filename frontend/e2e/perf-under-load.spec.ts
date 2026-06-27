/**
 * UI performance sampling while API load runs in parallel.
 *
 * Prereq: backend + frontend running; admin session cookie or use login flow.
 * Run:
 *   cd frontend && npx playwright test e2e/perf-under-load.spec.ts
 *
 * Env:
 *   PERF_BASE_URL=http://127.0.0.1:5173
 *   PERF_ADMIN_PASSWORD=...
 */
import { test, expect } from "@playwright/test";

const ADMIN_USER = process.env["PERF_ADMIN_USER"] || "ops.admin";
const ADMIN_PASS = process.env["PERF_ADMIN_PASSWORD"] || "ChangeMe123!";

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel(/username/i).fill(ADMIN_USER);
  await page.getByLabel(/password/i).fill(ADMIN_PASS);
  await page.getByRole("button", { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/(home|dashboard|loan)/, { timeout: 30_000 });
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
