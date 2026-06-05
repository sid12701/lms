/**
 * Phase 10 closeout — responsive overflow smoke.
 *
 * Asserts that the widest data-table surfaces don't cause horizontal page
 * overflow at any of the project's locked breakpoints. The structural
 * vitest-jsdom version of this lives in
 * `src/components/app/layout/__tests__/responsive-qa.test.tsx` but is
 * jsdom-pragmatic — it can't measure real layout. Playwright drives a real
 * Chromium so `scrollWidth` vs `clientWidth` is meaningful here.
 *
 * Breakpoints (locked by §5.1 / AppShell tier logic):
 *   - 375  iPhone-SE-ish minimum
 *   - 768  tablet portrait
 *   - 1024 lg — sidebar collapses to icon-only
 *   - 1280 xl — right-rail surfaces on detail pages (D6)
 *
 * One horizontal-overflow tolerance: the body's scrollWidth may exceed
 * viewport width by up to 1px due to subpixel rendering quirks in
 * Chromium. We assert `<= clientWidth + 1`.
 */
import { test, expect, type Page } from "@playwright/test";
import { signInAsSystemAdmin } from "./helpers/auth";

const VIEWPORTS = [
  { name: "mobile-375", width: 375, height: 812 },
  { name: "tablet-768", width: 768, height: 1024 },
  { name: "lg-1024", width: 1024, height: 768 },
  { name: "xl-1280", width: 1280, height: 800 },
] as const;

/**
 * Measure body scrollWidth vs documentElement clientWidth. Returns both so
 * a failure message can show the actual overflow magnitude.
 */
async function measureOverflow(page: Page): Promise<{ scrollWidth: number; clientWidth: number }> {
  return await page.evaluate(() => ({
    scrollWidth: document.body.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
}

const ROUTES: Array<{ name: string; path: string; waitFor: string }> = [
  { name: "home", path: "/home", waitFor: "main" },
  { name: "loan-applications", path: "/loan-applications", waitFor: "main" },
  { name: "reports", path: "/reports", waitFor: "main" },
  { name: "audit", path: "/audit", waitFor: "main" },
  { name: "lsps", path: "/lsps", waitFor: "main" },
  { name: "products", path: "/products", waitFor: "main" },
  { name: "users", path: "/users", waitFor: "main" },
  { name: "api-clients", path: "/api-clients", waitFor: "main" },
];

for (const route of ROUTES) {
  for (const viewport of VIEWPORTS) {
    test(`${route.name} has no horizontal overflow at ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await signInAsSystemAdmin(page);

      await page.goto(route.path);
      // Wait for the page's main landmark to be in the DOM — every authenticated
      // route renders inside AppShell which mounts <main id="main">.
      await page.waitForSelector(route.waitFor, { timeout: 15_000 });
      // One animation-frame for layout to settle after route-level Suspense.
      await page.evaluate(
        () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
      );

      const { scrollWidth, clientWidth } = await measureOverflow(page);
      expect(
        scrollWidth,
        `${route.name} @ ${viewport.name}: body.scrollWidth=${scrollWidth} > clientWidth=${clientWidth} (overflow=${scrollWidth - clientWidth}px)`,
      ).toBeLessThanOrEqual(clientWidth + 1);
    });
  }
}
