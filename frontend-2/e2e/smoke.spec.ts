import { test, expect } from "@playwright/test";

test("login page renders the Bhawana sign-in heading", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { level: 1, name: /Bhawana Capital/i }),
  ).toBeVisible();
  await expect(page.getByLabel("Demo accounts")).toBeVisible();
});
