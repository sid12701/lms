import { expect, test } from "@playwright/test";

const REFRESHED_TOKEN = {
  accessToken: "e2e-fresh-token",
  tokenType: "Bearer",
  expiresInSeconds: 1800,
  passwordChangeRequired: false,
};

const SYSTEM_CONTEXT = {
  application: "bhawana-lms",
  activeProfiles: ["e2e"],
  id: "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
  username: "ops.admin",
  roles: ["SYSTEM_ADMIN"],
  correlationId: null,
  lspId: null,
  lspName: null,
};

test("a transient context failure blocks cached permissions and recovers in place", async ({
  page,
}) => {
  let refreshRequests = 0;
  let contextRequests = 0;

  await page.addInitScript(() => {
    window.localStorage.setItem(
      "bhawana-lms-session",
      JSON.stringify({
        user: {
          id: "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
          username: "ops.admin",
          role: "SYSTEM_ADMIN",
          lspId: null,
          lspName: null,
          mustChangePassword: false,
        },
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );
  });

  await page.route("**/api/v1/auth/refresh", async (route) => {
    refreshRequests += 1;
    await route.fulfill({ status: 200, contentType: "application/json", json: REFRESHED_TOKEN });
  });
  await page.route("**/api/v1/internal/system/context", async (route) => {
    contextRequests += 1;
    if (contextRequests === 1) {
      await route.abort("failed");
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", json: SYSTEM_CONTEXT });
  });

  await page.goto("/loan-applications");

  await expect(page.getByRole("alert")).toContainText("We couldn't restore your session");
  await expect(page.getByTestId("loan-applications-page")).not.toBeVisible();
  await expect(page).toHaveURL(/\/loan-applications$/);

  await page.getByRole("button", { name: "Retry" }).click();

  await expect(page.getByRole("heading", { name: "Loan applications" })).toBeVisible();
  await expect(page.getByRole("complementary", { name: /Primary navigation/i })).toBeVisible();
  expect(refreshRequests).toBe(2);
  expect(contextRequests).toBe(2);
});
