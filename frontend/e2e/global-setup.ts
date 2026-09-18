import type { FullConfig } from "@playwright/test";
import {
  E2E_ADMIN_STORAGE_PATH,
  buildAdminStorageState,
  ensureBootstrapPasswordReady,
  isBackendHealthy,
  readFixturesFile,
  requiredAdminCredentials,
  resolveApiBase,
  resolveFrontendOrigin,
  seedLoanApplicationFixture,
  writeFixturesFile,
  type E2eFixturesFile,
} from "./helpers/e2e-fixtures";

async function ensureJourneyPasswordsReady(
  apiBase: string,
  admin: { email: string; password: string },
): Promise<void> {
  await ensureBootstrapPasswordReady(
    apiBase,
    admin.email,
    admin.password,
    process.env["E2E_ADMIN_INITIAL_PASSWORD"]?.trim(),
  );

  const lspEmail = process.env["E2E_LSP_UI_READ_EMAIL"]?.trim();
  const lspPassword = process.env["E2E_LSP_PASSWORD"]?.trim();
  const lspInitialPassword = process.env["E2E_LSP_INITIAL_PASSWORD"]?.trim();
  if (lspEmail && lspPassword && lspInitialPassword) {
    await ensureBootstrapPasswordReady(apiBase, lspEmail, lspPassword, lspInitialPassword);
  }
}

function skipFixtures(reason: string): E2eFixturesFile {
  const existing = readFixturesFile();
  const payload: E2eFixturesFile = {
    ...existing,
    skipReason: reason,
    apiBase: resolveApiBase(),
    createdAt: new Date().toISOString(),
  };
  writeFixturesFile(payload);
  console.warn(`[e2e globalSetup] Skipping fixture seed: ${reason}`);
  return payload;
}

export default async function globalSetup(_config: FullConfig): Promise<void> {
  const apiBase = resolveApiBase();
  const frontendOrigin = resolveFrontendOrigin();

  if (process.env["E2E_SKIP_FIXTURES"]?.toLowerCase() === "true") {
    skipFixtures("E2E_SKIP_FIXTURES=true");
    return;
  }

  const healthy = await isBackendHealthy(apiBase);
  if (!healthy) {
    skipFixtures(`backend not reachable at ${apiBase}`);
    return;
  }

  const presetApplicationId = process.env["E2E_APPLICATION_ID"]?.trim();
  if (presetApplicationId) {
    const payload: E2eFixturesFile = {
      applicationId: presetApplicationId,
      ec111ApplicationId: process.env["E2E_EC111_APPLICATION_ID"]?.trim(),
      apiBase,
      createdAt: new Date().toISOString(),
    };
    writeFixturesFile(payload);
    try {
      const { email, password } = requiredAdminCredentials();
      await ensureJourneyPasswordsReady(apiBase, { email, password });
      await buildAdminStorageState(
        apiBase,
        frontendOrigin,
        email,
        password,
        E2E_ADMIN_STORAGE_PATH,
      );
    } catch (error) {
      console.warn(
        `[e2e globalSetup] Using preset E2E_APPLICATION_ID without admin storage state: ${
          error instanceof Error ? error.message : String(error)
        }`,
      );
    }
    return;
  }

  let email: string;
  let password: string;
  try {
    ({ email, password } = requiredAdminCredentials());
  } catch (error) {
    skipFixtures(error instanceof Error ? error.message : String(error));
    return;
  }

  await ensureJourneyPasswordsReady(apiBase, { email, password });
  const fixtures = await seedLoanApplicationFixture(apiBase, email, password);
  writeFixturesFile(fixtures);
  await buildAdminStorageState(apiBase, frontendOrigin, email, password, E2E_ADMIN_STORAGE_PATH);

  console.log(
    `[e2e globalSetup] Seeded loan application ${fixtures.applicationId} (LSP ${fixtures.lspId})`,
  );
}
