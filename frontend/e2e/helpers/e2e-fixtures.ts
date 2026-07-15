import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export const E2E_AUTH_DIR = path.join(__dirname, "..", ".auth");
export const E2E_FIXTURES_PATH = path.join(E2E_AUTH_DIR, "e2e-fixtures.json");
export const E2E_ADMIN_STORAGE_PATH = path.join(E2E_AUTH_DIR, "phase8-admin.json");

export const DEFAULT_API_BASE = "http://localhost:8080";
export const DEFAULT_FRONTEND_ORIGIN = "http://localhost:5173";

const ROLE_PRIORITY = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_WRITE",
  "LSP_UI_READ",
  "LSP_API_CLIENT",
] as const;

export type E2eFixturesFile = {
  applicationId?: string;
  ec111ApplicationId?: string;
  lspId?: string;
  productId?: string;
  clientId?: string;
  clientSecret?: string;
  lspLoanId?: string;
  apiBase?: string;
  skipReason?: string;
  createdAt?: string;
};

export function resolveApiBase(): string {
  return (process.env["E2E_API_BASE"] ?? DEFAULT_API_BASE).replace(/\/$/, "");
}

export function resolveFrontendOrigin(): string {
  return (process.env["PLAYWRIGHT_BASE_URL"] ?? DEFAULT_FRONTEND_ORIGIN).replace(/\/$/, "");
}

export function readFixturesFile(): E2eFixturesFile | null {
  if (!fs.existsSync(E2E_FIXTURES_PATH)) return null;
  try {
    return JSON.parse(fs.readFileSync(E2E_FIXTURES_PATH, "utf8")) as E2eFixturesFile;
  } catch {
    return null;
  }
}

export function writeFixturesFile(data: E2eFixturesFile): void {
  fs.mkdirSync(E2E_AUTH_DIR, { recursive: true });
  fs.writeFileSync(E2E_FIXTURES_PATH, `${JSON.stringify(data, null, 2)}\n`, "utf8");
}

/** Phase 8: env override, then globalSetup fixture file. */
export function resolveApplicationId(): string {
  const fromEnv = process.env["E2E_APPLICATION_ID"]?.trim();
  if (fromEnv) return fromEnv;
  const fromFile = readFixturesFile()?.applicationId?.trim();
  return fromFile ?? "";
}

export function resolveEc111ApplicationId(fallbackApplicationId: string): string {
  const fromEnv = process.env["E2E_EC111_APPLICATION_ID"]?.trim();
  if (fromEnv) return fromEnv;
  const fromFile = readFixturesFile()?.ec111ApplicationId?.trim();
  return fromFile ?? fallbackApplicationId;
}

export function requiredAdminCredentials(): { email: string; password: string } {
  const email = process.env["E2E_ADMIN_EMAIL"]?.trim();
  const password = process.env["E2E_ADMIN_PASSWORD"]?.trim();
  if (!email || !password) {
    throw new Error(
      "E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD are required to API-seed Playwright fixtures. " +
        "Set them in the environment or export E2E_APPLICATION_ID manually.",
    );
  }
  return { email, password };
}

export async function isBackendHealthy(apiBase: string): Promise<boolean> {
  try {
    const res = await fetch(`${apiBase}/api/v1/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: "e2e-health@probe.invalid", password: "probe" }),
      signal: AbortSignal.timeout(5_000),
    });
    return res.status > 0;
  } catch {
    return false;
  }
}

type JsonRecord = Record<string, unknown>;

async function parseJson<T extends JsonRecord>(res: Response, label: string): Promise<T> {
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${label} failed (${res.status}): ${body.slice(0, 400)}`);
  }
  return (await res.json()) as T;
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
}

function borrowerIds(suffix: string): { aadhar: string; pan: string; mobile: string } {
  const digest = crypto.createHash("sha256").update(suffix).digest();
  const n = digest.readUInt32BE(0);
  const aadhar = String(n % 10 ** 12).padStart(12, "0");
  const pan = `ABCDE${String(n % 10_000).padStart(4, "0")}F`;
  const mobile = `9${String(n % 10 ** 9).padStart(9, "0")}`;
  return { aadhar, pan, mobile };
}

function loanApplicationBody(lspId: string, productId: string, suffix: string): JsonRecord {
  const { aadhar, pan, mobile } = borrowerIds(suffix);
  const fullName = `E2E Borrower ${suffix}`;
  return {
    lspId,
    productId,
    lspLoanId: `E2E-EXT-${suffix}`,
    fullName,
    emailAddress: `e2e.${suffix}@example.com`,
    mobileNumber: mobile,
    dob: "1990-05-15",
    gender: "MALE",
    maritalStatus: "SINGLE",
    fatherName: "Parent",
    aadharNumber: aadhar,
    panNumber: pan,
    loanAmount: 150_000,
    interestRate: 14.5,
    loanTenure: 12,
    addressLine1: "42 Demo Street",
    addressCity: "Mumbai",
    addressState: "MH",
    addressZipcode: "400001",
    employmentStatus: "SALARIED",
    organizationName: "Demo Corp",
    monthlyIncome: 60_000,
    annualIncome: 720_000,
    bankAccountNumber: "1234567890",
    bankName: "HDFC Bank",
    ifscCode: "HDFC0001234",
    accountHolderName: fullName,
    referencePersonName: "Ref",
    referencePersonNumber: "9123456780",
  };
}

function parseSetCookieHeaders(setCookie: string[]): Array<{
  name: string;
  value: string;
  domain: string;
  path: string;
  httpOnly: boolean;
  secure: boolean;
  sameSite: "Strict" | "Lax" | "None";
}> {
  return setCookie.map((raw) => {
    const parts = raw.split(";").map((p) => p.trim());
    const [nameValue, ...attrs] = parts;
    const eq = nameValue?.indexOf("=") ?? -1;
    const name = eq >= 0 ? nameValue!.slice(0, eq) : "";
    const value = eq >= 0 ? nameValue!.slice(eq + 1) : "";
    let cookiePath = "/api/v1/auth";
    let secure = false;
    let httpOnly = false;
    let sameSite: "Strict" | "Lax" | "None" = "Strict";
    for (const attr of attrs) {
      const lower = attr.toLowerCase();
      if (lower.startsWith("path=")) cookiePath = attr.slice(5);
      if (lower === "secure") secure = true;
      if (lower === "httponly") httpOnly = true;
      if (lower.startsWith("samesite=")) {
        const ss = attr.slice(9);
        if (ss === "Lax" || ss === "None") sameSite = ss;
      }
    }
    return {
      name,
      value,
      domain: "localhost",
      path: cookiePath,
      httpOnly,
      secure,
      sameSite,
    };
  });
}

export async function adminLogin(
  apiBase: string,
  email: string,
  password: string,
): Promise<{ accessToken: string; setCookies: string[] }> {
  const res = await fetch(`${apiBase}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const body = await parseJson<{ accessToken: string }>(res, "Admin login");
  const setCookies =
    typeof res.headers.getSetCookie === "function" ? res.headers.getSetCookie() : [];
  return { accessToken: body.accessToken, setCookies };
}

export async function buildAdminStorageState(
  apiBase: string,
  frontendOrigin: string,
  email: string,
  password: string,
  outPath: string,
): Promise<void> {
  const loginRes = await fetch(`${apiBase}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const tokenBody = await parseJson<{
    accessToken: string;
    expiresInSeconds?: number;
    passwordChangeRequired?: boolean;
  }>(loginRes, "Admin login (storage state)");
  const accessToken = tokenBody.accessToken;
  const loginCookies =
    typeof loginRes.headers.getSetCookie === "function" ? loginRes.headers.getSetCookie() : [];

  const cookieHeader = loginCookies.map((c) => c.split(";")[0]).join("; ");
  const ctxRes = await fetch(`${apiBase}/api/v1/internal/system/context`, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      ...(cookieHeader ? { Cookie: cookieHeader } : {}),
    },
  });
  const context = await parseJson<{
    id: string;
    username: string;
    roles?: string[];
    lspId?: string | null;
  }>(ctxRes, "System context");

  const expiresIn = Math.max(60, tokenBody.expiresInSeconds ?? 3600);
  const expiresAt = new Date(Date.now() + expiresIn * 1000).toISOString().replace("+00:00", "Z");
  const roles = context.roles ?? [];
  const role =
    ROLE_PRIORITY.find((candidate) => roles.includes(candidate)) ?? roles[0] ?? "OPS_USER";

  const session = {
    user: {
      id: context.id,
      username: context.username,
      role,
      lspId: context.lspId ?? null,
      mustChangePassword: Boolean(tokenBody.passwordChangeRequired),
    },
    expiresAt,
  };

  const state = {
    cookies: parseSetCookieHeaders(loginCookies),
    origins: [
      {
        origin: frontendOrigin,
        localStorage: [{ name: "bhawana-lms-session", value: JSON.stringify(session) }],
      },
    ],
  };

  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(state), "utf8");
}

/** Admin + LSP API sequence mirrored from DocumentUploadTestSupport / scripts/e2e/fixtures.py. */
export async function seedLoanApplicationFixture(
  apiBase: string,
  email: string,
  password: string,
): Promise<E2eFixturesFile> {
  const { accessToken } = await adminLogin(apiBase, email, password);
  const ah = authHeaders(accessToken);
  const suffix = crypto.randomUUID().replace(/-/g, "").slice(0, 12);
  const tag = suffix.slice(-8);

  const lspRes = await fetch(`${apiBase}/api/v1/internal/admin/lsps`, {
    method: "POST",
    headers: ah,
    body: JSON.stringify({ code: `E2E-${tag}`, name: `E2E LSP ${tag}`, status: "ACTIVE" }),
  });
  const lsp = await parseJson<{ id: string }>(lspRes, "Create LSP");

  const productRes = await fetch(`${apiBase}/api/v1/internal/admin/products`, {
    method: "POST",
    headers: ah,
    body: JSON.stringify({
      code: `E2E-P-${tag}`,
      name: `E2E Product ${tag}`,
      minPrincipal: 10_000,
      maxPrincipal: 500_000,
      interestRate: 14.5,
      processingFeeRate: 1.5,
      minTenureMonths: 6,
      maxTenureMonths: 36,
      status: "ACTIVE",
    }),
  });
  const product = await parseJson<{ id: string }>(productRes, "Create product");

  const mapRes = await fetch(`${apiBase}/api/v1/internal/admin/products/${product.id}/mappings`, {
    method: "PUT",
    headers: ah,
    body: JSON.stringify({ lspIds: [lsp.id] }),
  });
  if (!mapRes.ok) {
    const body = await mapRes.text();
    throw new Error(`Map product failed (${mapRes.status}): ${body.slice(0, 400)}`);
  }

  const clientRes = await fetch(`${apiBase}/api/v1/internal/admin/api-clients`, {
    method: "POST",
    headers: ah,
    body: JSON.stringify({ lspId: lsp.id, name: `e2e-client-${tag}` }),
  });
  const client = await parseJson<{ clientId: string; clientSecret: string }>(
    clientRes,
    "Create API client",
  );

  const tokenRes = await fetch(`${apiBase}/api/v1/auth/token`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ clientId: client.clientId, clientSecret: client.clientSecret }),
  });
  const lspToken = (await parseJson<{ accessToken: string }>(tokenRes, "LSP token")).accessToken;

  const loanBody = loanApplicationBody(lsp.id, product.id, suffix);
  const appRes = await fetch(`${apiBase}/api/v1/lsp/loan-applications`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${lspToken}`,
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
    },
    body: JSON.stringify(loanBody),
  });
  const application = await parseJson<{ id: string }>(appRes, "Create loan application");

  return {
    applicationId: application.id,
    lspId: lsp.id,
    productId: product.id,
    clientId: client.clientId,
    clientSecret: client.clientSecret,
    lspLoanId: String(loanBody.lspLoanId),
    apiBase,
    createdAt: new Date().toISOString(),
  };
}

export async function invalidateLoanApplication(
  apiBase: string,
  fixtures: E2eFixturesFile,
): Promise<void> {
  if (!fixtures.applicationId || !fixtures.clientId || !fixtures.clientSecret) return;

  const clientSecret = fixtures.clientSecret;
  const tokenRes = await fetch(`${apiBase}/api/v1/auth/token`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ clientId: fixtures.clientId, clientSecret }),
  });
  if (!tokenRes.ok) return;

  const lspToken = (await tokenRes.json()) as { accessToken?: string };
  if (!lspToken.accessToken) return;

  await fetch(`${apiBase}/api/v1/lsp/loan-applications/${fixtures.applicationId}/invalid`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${lspToken.accessToken}`,
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
    },
    body: JSON.stringify({
      reasonCode: "BORROWER_WITHDREW",
      reasonText: "Playwright E2E global teardown",
    }),
  });
}
