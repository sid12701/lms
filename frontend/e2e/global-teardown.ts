import type { FullConfig } from "@playwright/test";
import {
  invalidateLoanApplication,
  readFixturesFile,
  resolveApiBase,
} from "./helpers/e2e-fixtures";

/**
 * Best-effort cleanup: invalidate the seeded loan via the LSP API when credentials
 * are still available. LSP/product/client rows are left in place (no admin delete API).
 */
export default async function globalTeardown(_config: FullConfig): Promise<void> {
  if (process.env["E2E_SKIP_FIXTURES"]?.toLowerCase() === "true") return;

  const fixtures = readFixturesFile();
  if (!fixtures?.applicationId || fixtures.skipReason) return;

  const apiBase = fixtures.apiBase ?? resolveApiBase();
  try {
    await invalidateLoanApplication(apiBase, fixtures);
    console.log(`[e2e globalTeardown] Invalidated loan application ${fixtures.applicationId}`);
  } catch (error) {
    console.warn(
      `[e2e globalTeardown] Could not invalidate ${fixtures.applicationId}: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  }
}
