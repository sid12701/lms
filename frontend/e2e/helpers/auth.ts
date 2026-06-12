import { expect, type Page } from "@playwright/test";

const DEFAULT_ADMIN = {
  username: process.env["E2E_ADMIN_USERNAME"] ?? "ops.admin",
  password: process.env["E2E_ADMIN_PASSWORD"] ?? "ChangeMe123!",
};

/** Sign in as the bootstrap system administrator via the login role picker. */
export async function signInAsSystemAdmin(page: Page): Promise<void> {
  await page.goto("/login");
  const preset = page.getByLabel(/Fill credentials for System administrator/i);
  if (await preset.isVisible().catch(() => false)) {
    await preset.click();
  } else {
    await page.getByLabel(/^Username$/i).fill(DEFAULT_ADMIN.username);
    await page.getByLabel(/^Password$/i).fill(DEFAULT_ADMIN.password);
  }
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL(/\/home$/);
  await expect(page.getByRole("complementary", { name: /Primary navigation/i })).toBeVisible({
    timeout: 15_000,
  });
}
