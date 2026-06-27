import { expect, type Page } from "@playwright/test";

const DEFAULT_ADMIN = {
  email: process.env["E2E_ADMIN_EMAIL"] ?? "siddhant@bhawanafinance.com",
  password: process.env["E2E_ADMIN_PASSWORD"] ?? "ChangeMe123!",
};

const DEFAULT_LSP_READ = {
  email: process.env["E2E_LSP_UI_READ_EMAIL"] ?? "lsp.read1@bhawana.local",
  password: process.env["E2E_LSP_PASSWORD"] ?? "DemoPass123!",
};

async function signInViaLoginPage(
  page: Page,
  presetLabel: RegExp,
  fallback: { email: string; password: string },
  landingUrl: RegExp,
): Promise<void> {
  await page.goto("/login");
  const preset = page.getByLabel(presetLabel);
  if (await preset.isVisible().catch(() => false)) {
    await preset.click();
  } else {
    await page.getByLabel(/^Email$/i).fill(fallback.email);
    await page.getByLabel(/^Password$/i).fill(fallback.password);
  }
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL(landingUrl);
  await expect(page.getByRole("complementary", { name: /Primary navigation/i })).toBeVisible({
    timeout: 15_000,
  });
}

/** Sign in as the bootstrap system administrator via the login role picker. */
export async function signInAsSystemAdmin(page: Page): Promise<void> {
  await signInViaLoginPage(
    page,
    /Fill credentials for System administrator/i,
    DEFAULT_ADMIN,
    /\/home$/,
  );
}

/** Sign in as an LSP read-only user — lands on `/my-loans`. */
export async function signInAsLspUser(page: Page): Promise<void> {
  await signInViaLoginPage(
    page,
    /Fill credentials for LSP user \(read-only\)/i,
    DEFAULT_LSP_READ,
    /\/my-loans$/,
  );
}
