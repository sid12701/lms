import { test, expect } from "@playwright/test";
import { LOGIN_PAGE_HEADING } from "../src/lib/product-branding";

test("login page renders the Bhawana sign-in form", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("heading", { level: 1, name: LOGIN_PAGE_HEADING })).toBeVisible();
  await expect(page.getByLabel(/^Email$/i)).toBeVisible();
  await expect(page.getByLabel(/^Password$/i)).toBeVisible();
  await expect(page.getByRole("button", { name: /^Sign in$/i })).toBeVisible();
});
