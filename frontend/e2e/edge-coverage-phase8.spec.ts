/**
 * Phase 8 — UI edge cases (EC-083, EC-085–090, EC-096, EC-097, EC-111).
 *
 * Run via: python scripts/e2e/run_coverage.py --phase 8
 * Env: E2E_APPLICATION_ID, E2E_API_BASE, E2E_STORAGE_STATE (pre-built by phase8_ui.py)
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect, type Page } from "@playwright/test";
import {
  DEFAULT_API_BASE,
  E2E_ADMIN_STORAGE_PATH,
  readFixturesFile,
  resolveApplicationId,
  resolveEc111ApplicationId,
} from "./helpers/e2e-fixtures";

const AUTH_FILE = process.env["E2E_STORAGE_STATE"] ?? E2E_ADMIN_STORAGE_PATH;

const APPLICATION_ID = resolveApplicationId();
const EC111_APPLICATION_ID = resolveEc111ApplicationId(APPLICATION_ID);
const API_BASE = process.env["E2E_API_BASE"] ?? readFixturesFile()?.apiBase ?? DEFAULT_API_BASE;

const fixtureSkipReason = readFixturesFile()?.skipReason;
test.skip(
  !APPLICATION_ID,
  fixtureSkipReason ??
    "E2E_APPLICATION_ID is unset — run Playwright globalSetup (see docs/e2e.md) or export an application id",
);

async function adminToken(request: import("@playwright/test").APIRequestContext): Promise<string> {
  const email = process.env["E2E_ADMIN_EMAIL"];
  const password = process.env["E2E_ADMIN_PASSWORD"];
  if (!email || !password) {
    throw new Error("E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD are required for Phase 8 API helpers");
  }
  const res = await request.post(`${API_BASE}/api/v1/auth/login`, {
    data: { email, password },
  });
  expect(res.ok()).toBeTruthy();
  const body = (await res.json()) as { accessToken: string };
  return body.accessToken;
}

async function apiLoanDetail(request: import("@playwright/test").APIRequestContext, token: string) {
  const res = await request.get(
    `${API_BASE}/api/v1/internal/ops/loan-applications/${APPLICATION_ID}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(res.ok()).toBeTruthy();
  return res.json() as Promise<{ status: string }>;
}

async function ensureAuthenticated(page: Page): Promise<void> {
  await page.goto("/home");
  await expect(page).not.toHaveURL(/\/login/, { timeout: 30_000 });
  await page.waitForFunction(() => !document.querySelector('[aria-busy="true"]'), {
    timeout: 30_000,
  });
  await expect(page.getByRole("form", { name: /Sign in/i })).not.toBeVisible();
}

async function openLoanDetail(page: Page): Promise<void> {
  await ensureAuthenticated(page);
  await page.goto(`/loan-applications/${APPLICATION_ID}`);
  await expect(page).not.toHaveURL(/\/login/, { timeout: 20_000 });
  const detail = page.locator('[data-testid="loan-application-detail"]');
  await expect(detail).toBeVisible({ timeout: 45_000 });
  await expect(detail).not.toHaveAttribute("data-loading", "true", { timeout: 45_000 });
  await expect(detail).not.toHaveAttribute("data-state", "not-found", { timeout: 5_000 });
  await expect(detail).not.toHaveAttribute("data-state", "forbidden", { timeout: 5_000 });
  await expect(page.locator('[data-slot="status-badge"]').first()).toBeVisible({ timeout: 45_000 });
}

test.beforeAll(() => {
  if (process.env["E2E_STORAGE_STATE"] && fs.existsSync(process.env["E2E_STORAGE_STATE"])) {
    fs.copyFileSync(process.env["E2E_STORAGE_STATE"], AUTH_FILE);
  }
});

test.afterEach(async ({ context }) => {
  const out = process.env["E2E_STORAGE_STATE"] ?? AUTH_FILE;
  fs.mkdirSync(path.dirname(out), { recursive: true });
  await context.storageState({ path: out });
  if (out !== AUTH_FILE) {
    fs.copyFileSync(out, AUTH_FILE);
  }
});

test.describe.configure({ mode: "serial" });
test.use({ storageState: AUTH_FILE });
test.setTimeout(90_000);

test.describe("Phase 8 UI edge coverage", () => {
  test("EC-083: refresh preserves session on loan detail", async ({ page }) => {
    await openLoanDetail(page);
    await page.reload();
    await expect(page).not.toHaveURL(/\/login/);
    await expect(page.locator('[data-testid="loan-application-detail"]')).toBeVisible({
      timeout: 20_000,
    });
  });

  test("EC-085: no console.error on primary screens", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") errors.push(msg.text());
    });
    page.on("pageerror", (err) => errors.push(err.message));

    await ensureAuthenticated(page);
    for (const route of ["/home", "/loan-applications", "/audit", "/borrowers"]) {
      await page.goto(route);
      await page.waitForFunction(() => !document.querySelector('[aria-busy="true"]'), {
        timeout: 30_000,
      });
      await expect(page.getByRole("form", { name: /Sign in/i })).not.toBeVisible();
      await page.waitForLoadState("domcontentloaded");
    }
    expect(errors, `console errors: ${errors.join("; ")}`).toEqual([]);
  });

  test("EC-086: blocked API shows error state", async ({ page }) => {
    await page.route(`**/api/v1/internal/ops/loan-applications/${APPLICATION_ID}**`, (route) =>
      route.abort("failed"),
    );
    await page.goto(`/loan-applications/${APPLICATION_ID}`);
    await expect(
      page.locator('[data-testid="loan-application-detail"][data-state="error"]'),
    ).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByText(/Couldn't load this application/i)).toBeVisible();
  });

  test("EC-087: filtered empty state on loan applications", async ({ page }) => {
    await page.goto("/loan-applications");
    await page.getByLabel("Bhaw loan ID").fill("00000000-0000-0000-0000-000000000000");
    await expect(page.getByText(/No applications match these filters/i)).toBeVisible({
      timeout: 15_000,
    });
  });

  test("EC-088: audit pagination reaches last page", async ({ page }) => {
    await page.goto("/audit");
    await expect(page.getByRole("heading", { name: /Audit/i })).toBeVisible({ timeout: 15_000 });

    const lastBtn = page.getByRole("button", { name: /Go to last page/i });
    const firstBtn = page.getByRole("button", { name: /Go to first page/i });
    const prevBtn = page.getByRole("button", { name: /Go to previous page/i });

    if (await lastBtn.isEnabled()) {
      await lastBtn.click();
      await expect(page.getByRole("button", { name: /Go to next page/i })).toBeDisabled();
      await firstBtn.click();
    }
    await expect(prevBtn).toBeDisabled();
  });

  test("EC-089: status and LSP filters combine in URL", async ({ page }) => {
    await page.goto("/loan-applications");
    await page.getByRole("button", { name: /All statuses/i }).click();
    await page.getByRole("option", { name: /Disbursed/i }).click();
    await page.keyboard.press("Escape");

    const lspTrigger = page.locator('[data-slot="loan-applications-lsp-filter"]');
    await expect(lspTrigger).toBeVisible({ timeout: 10_000 });
    await lspTrigger.click();
    const option = page.getByRole("option").nth(1);
    if (await option.isVisible()) {
      await option.click();
      await expect(page).toHaveURL(/lspId=/);
    }
    await expect(page).toHaveURL(/status=/);
  });

  test("EC-090: PII fields render masked aadhaar", async ({ page }) => {
    await openLoanDetail(page);
    const aadhaar = page.locator('[data-slot="aadhaar"]');
    await expect(aadhaar).toBeVisible();
    await expect(aadhaar).toHaveText(/^X{4,}\d{4}$/, { timeout: 30_000 });
  });

  test("EC-096: UI status badge matches API status", async ({ page, request }) => {
    const token = await adminToken(request);
    const detail = await apiLoanDetail(request, token);
    await openLoanDetail(page);
    const badge = page.locator('[data-slot="status-badge"]').first();
    await expect(badge).toBeVisible();
    const label = (await badge.innerText()).replace(/\s+/g, " ").trim().toLowerCase();
    expect(label).toContain(detail.status.replace(/_/g, " ").toLowerCase().split(" ")[0]!);
  });

  test("EC-097: webhook tab row count matches API", async ({ page, request }) => {
    const token = await adminToken(request);
    const res = await request.get(
      `${API_BASE}/api/v1/internal/ops/loan-applications/${APPLICATION_ID}/webhook-events`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(res.ok()).toBeTruthy();
    const body = (await res.json()) as { deliveries?: unknown[] };
    const apiCount = body.deliveries?.length ?? 0;

    await openLoanDetail(page);
    await page.getByRole("tab", { name: /Webhooks/i }).click();
    if (apiCount === 0) {
      await expect(page.getByText(/No webhook deliveries yet/i)).toBeVisible({ timeout: 10_000 });
    } else {
      await expect(page.locator('[data-slot="webhooks-tab"] tbody tr')).toHaveCount(apiCount, {
        timeout: 15_000,
      });
    }
  });

  test("EC-111: UI stays stale after API status change (known gap)", async ({ page, request }) => {
    const token = await adminToken(request);
    const detail = (await (
      await request.get(
        `${API_BASE}/api/v1/internal/ops/loan-applications/${EC111_APPLICATION_ID}`,
        {
          headers: { Authorization: `Bearer ${token}` },
        },
      )
    ).json()) as { status: string };

    test.skip(detail.status !== "DISBURSED", "EC-111 needs a DISBURSED fixture");

    const scheduleRes = await request.get(
      `${API_BASE}/api/v1/internal/ops/loan-applications/${EC111_APPLICATION_ID}/repayment-schedule`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(scheduleRes.ok()).toBeTruthy();
    const scheduleBody = (await scheduleRes.json()) as
      | { id: string; amount?: number; installmentAmount?: number; status?: string }[]
      | {
          installments?: {
            id: string;
            amount?: number;
            installmentAmount?: number;
            status?: string;
          }[];
        };
    const installments = Array.isArray(scheduleBody)
      ? scheduleBody
      : (scheduleBody.installments ?? []);
    const inst = installments.find((row) => row.status !== "PAID") ?? installments[0];
    test.skip(!inst, "No installment on schedule for payment trigger");

    await ensureAuthenticated(page);
    await page.goto(`/loan-applications/${EC111_APPLICATION_ID}`);
    await expect(page.locator('[data-testid="loan-application-detail"]')).toBeVisible({
      timeout: 45_000,
    });
    await expect(page.locator('[data-slot="status-badge"]').first()).toContainText(/disbursed/i);

    await request.post(
      `${API_BASE}/api/v1/internal/ops/loan-applications/${EC111_APPLICATION_ID}/payments`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
        },
        data: {
          targetInstallmentId: inst!.id,
          amount: inst!.amount ?? inst!.installmentAmount,
          postedAt: "2026-06-11",
          reference: "EC-111",
          channel: "NEFT",
        },
      },
    );

    await page.waitForTimeout(10_000);
    await expect(page.locator('[data-slot="status-badge"]').first()).toContainText(/disbursed/i);
  });
});
