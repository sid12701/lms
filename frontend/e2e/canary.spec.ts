/**
 * Post-deploy smoke (@canary): login → Home KPIs → Audit rows.
 *
 * Run:
 *   E2E_ADMIN_EMAIL=… E2E_ADMIN_PASSWORD=… npm run e2e:canary
 *
 * Requires backend on VITE_API_BASE_URL / localhost:8080 and Vite on :5173.
 */
import { test, expect } from "@playwright/test";
import { signInAsSystemAdmin } from "./helpers/auth";

test.describe("protected-route canary @canary", () => {
  test("login → home KPIs → audit log rows", async ({ page }) => {
    await signInAsSystemAdmin(page);

    await page.goto("/home");
    await expect(page.getByTestId("home-page-internal")).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('[data-slot="kpi-tile"]').first()).toBeVisible({ timeout: 30_000 });

    await page.goto("/audit");
    await expect(page.getByTestId("audit-page")).toBeVisible({ timeout: 30_000 });
    await expect(
      page
        .locator("table tbody tr")
        .first()
        .or(page.getByText(/No audit|No events|Nothing to show|0 events/i)),
    ).toBeVisible({ timeout: 30_000 });
  });
});
