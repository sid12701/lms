import { defineConfig } from "@playwright/test";

/**
 * H22 isolated cookie-transport config. Uses installed Google Chrome
 * (channel: chrome) so no browser download is required, starts no
 * frontend webServer and no backend fixture seeding — the spec boots its
 * own same-origin Vite + HTTP harness on ephemeral ports.
 */
export default defineConfig({
  testDir: "./e2e",
  testMatch: ["h22-cookie-transport.spec.ts"],
  fullyParallel: false,
  forbidOnly: !!process.env["CI"],
  retries: 0,
  workers: 1,
  timeout: 90000,
  reporter: "list",
  projects: [
    {
      name: "chromium",
      use: {
        channel: "chrome",
        launchOptions: {
          args: ["--no-sandbox"],
        },
      },
    },
  ],
});
