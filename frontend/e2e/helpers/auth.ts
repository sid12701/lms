import { expect, type Page } from "@playwright/test";

/** Sign in as the bootstrap system administrator via the login role picker. */
export async function signInAsSystemAdmin(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabelText(/Fill credentials for System administrator/i).click();
  await page.getByRole("button", { name: /^sign in$/i }).click();
  await page.waitForURL(/\/home$/);
  await expect(page.getByRole("complementary", { name: /Primary navigation/i })).toBeVisible({
    timeout: 15_000,
  });
}
