import { test, expect } from "@playwright/test";
import { LOGIN_PAGE_HEADING } from "../src/lib/product-branding";

test("login page renders the Bhawana sign-in heading", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1, name: LOGIN_PAGE_HEADING })).toBeVisible();
  await expect(page.getByLabel("System roles")).toBeVisible();
});
