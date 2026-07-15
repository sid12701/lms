import { expect, type Page } from "@playwright/test";

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(
      `Missing required E2E env var ${name}. Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD (and LSP vars when needed).`,
    );
  }
  return value;
}

/** Sign in via the shipped Email/Password form (no DEV role picker). */
export async function signInWithCredentials(
  page: Page,
  email: string,
  password: string,
  landingUrl: RegExp,
): Promise<void> {
  await page.goto("/login");
  await page.getByLabel(/^Email$/i).fill(email);
  await page.getByLabel(/^Password$/i).fill(password);
  await page.getByRole("button", { name: /^Sign in$/i }).click();
  await page.waitForURL(landingUrl, { timeout: 30_000 });
  await expect(page.getByRole("complementary", { name: /Primary navigation/i })).toBeVisible({
    timeout: 15_000,
  });
}

/** Sign in as the bootstrap system administrator. Requires E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD. */
export async function signInAsSystemAdmin(page: Page): Promise<void> {
  await signInWithCredentials(
    page,
    requiredEnv("E2E_ADMIN_EMAIL"),
    requiredEnv("E2E_ADMIN_PASSWORD"),
    /\/home$/,
  );
}

/** Sign in as an LSP read-only user — lands on `/my-loans`. */
export async function signInAsLspUser(page: Page): Promise<void> {
  await signInWithCredentials(
    page,
    requiredEnv("E2E_LSP_UI_READ_EMAIL"),
    requiredEnv("E2E_LSP_PASSWORD"),
    /\/my-loans$/,
  );
}
