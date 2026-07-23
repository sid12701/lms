/**
 * Borrower-detail API client.
 *
 * For internal sessions (SYSTEM_ADMIN / OPS_USER) the read endpoint calls
 * the live backend at `/api/v1/internal/admin/borrowers/{id}` and
 * translates the flat backend `BorrowerDetailResponse` into the nested
 * `BorrowerDetail` projection. High-risk PII (Aadhaar, bank account) is
 * masked server-side by the backend before it reaches the wire, so the
 * frontend never holds the cleartext on the read path.
 *
 * Per the masking-everywhere posture (see `docs/gap-fixes.md` § Gap #1),
 * there is no audited PII reveal endpoint — values are always masked.
 */
import { requestJson } from "@/lib/api/http-client";
import { listLspOptions } from "@/features/lsps/options";
import { finiteNumberOrZero as toNumber } from "@/lib/number";
import type { BorrowerDetail } from "./types";

const BACKEND_BASE = "/api/v1/internal/admin/borrowers";

interface BackendBorrowerLoanRow {
  loanAccountId: string | null;
  applicationId: string | null;
  accountNumber: string | null;
  lspId: string | null;
  lspCode: string | null;
  lspName: string | null;
  loanProductCode: string | null;
  status: string | null;
  principalAmount: number | string | null;
  tenureMonths: number;
  approvedAt: string | null;
  disbursedAt: string | null;
  closureReason: string | null;
  closedAt: string | null;
  closedByUsername: string | null;
  createdAt: string;
}

interface BackendBorrowerDetail {
  id: string;
  fullName: string;
  pan: string | null;
  mobile: string | null;
  email: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  maritalStatus: string | null;
  fatherName: string | null;
  aadharNumberMasked: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  state: string | null;
  addressZipCode: string | null;
  spouseName: string | null;
  employmentType: string | null;
  organizationName: string | null;
  employeeId: string | null;
  employmentCity: string | null;
  employmentState: string | null;
  employmentZip: string | null;
  monthlyIncome: number | string | null;
  annualIncome: number | string | null;
  bankAccountNumberMasked: string | null;
  bankName: string | null;
  ifscCode: string | null;
  accountHolderName: string | null;
  referencePersonName: string | null;
  referencePersonNumber: string | null;
  visibleLspIds: string[];
  visibleLsps?: ReadonlyArray<{
    id: string;
    code: string;
    name: string;
    firstSourcedAt?: string | null;
    lastTouchedAt?: string | null;
    sourceChannel?: string | null;
  }>;
  loans: BackendBorrowerLoanRow[];
  delinquency: BackendBorrowerDelinquency | null;
}

/**
 * Gap #6: aggregate delinquency across the borrower's active loans.
 * `bucket` is null when no overdue installments exist.
 */
interface BackendBorrowerDelinquency {
  activeOverdueAmount: number | string;
  maxDaysPastDue: number;
  overdueLoanCount: number;
  bucket: string | null;
}

const OPEN_STATUSES = new Set([
  "INITIALIZED",
  "AWAITING_APPROVAL",
  "APPROVED_PENDING_DISBURSAL",
  "DISBURSEMENT_RETRY",
  "DISBURSED",
  "UNDER_REPAYMENT",
]);

const CLOSED_STATUSES = new Set(["CLOSED", "FORECLOSED", "REJECTED", "INVALID"]);

function toVisibleLsps(
  payload: BackendBorrowerDetail,
  lspNamesById: ReadonlyMap<string, string> = new Map(),
): BorrowerDetail["visibleLsps"] {
  if (payload.visibleLsps && payload.visibleLsps.length > 0) {
    return payload.visibleLsps.map((lsp) => ({
      id: lsp.id,
      name: lsp.name,
      firstSourcedAt: lsp.firstSourcedAt ?? null,
      lastTouchedAt: lsp.lastTouchedAt ?? null,
      sourceChannel: lsp.sourceChannel ?? null,
    }));
  }

  const namesById = new Map<string, string>();
  for (const loan of payload.loans) {
    const name = loan.lspName ?? loan.lspCode;
    if (loan.lspId && name) namesById.set(loan.lspId, name);
  }
  for (const id of payload.visibleLspIds ?? []) {
    if (!namesById.has(id)) namesById.set(id, lspNamesById.get(id) ?? id);
  }
  return Array.from(namesById, ([id, name]) => ({ id, name }));
}

function summarizeLoans(payload: BackendBorrowerDetail): BorrowerDetail["totals"] {
  let openCount = 0;
  let closedCount = 0;
  let lifetimeDisbursed = 0;
  for (const loan of payload.loans) {
    const status = loan.status ?? "";
    const isOpen = OPEN_STATUSES.has(status);
    if (isOpen) openCount += 1;
    else if (CLOSED_STATUSES.has(status)) closedCount += 1;
    if (loan.disbursedAt) lifetimeDisbursed += toNumber(loan.principalAmount);
  }
  return {
    openApplicationsCount: openCount,
    closedApplicationsCount: closedCount,
    lifetimeDisbursedAmount: lifetimeDisbursed,
    activeOverdueAmount: toNumber(payload.delinquency?.activeOverdueAmount ?? 0),
  };
}

function toBorrowerAddress(payload: BackendBorrowerDetail): BorrowerDetail["borrower"]["address"] {
  return {
    residential: [payload.addressLine1, payload.addressLine2].filter(Boolean).join(", ") || "",
    city: payload.city ?? "",
    state: payload.state ?? "",
    zip: payload.addressZipCode ?? "",
  };
}

function toBorrowerEmployment(
  payload: BackendBorrowerDetail,
): BorrowerDetail["borrower"]["employment"] {
  return {
    type: (payload.employmentType ?? "SALARIED") as
      | "SALARIED"
      | "SELF_EMPLOYED"
      | "BUSINESS"
      | "RETIRED"
      | "STUDENT"
      | "UNEMPLOYED",
    organization: payload.organizationName,
    employeeId: payload.employeeId,
    location: [payload.employmentCity, payload.employmentState].filter(Boolean).join(", ") || null,
    monthlyIncome: toNumber(payload.monthlyIncome),
    annualIncome: toNumber(payload.annualIncome),
  };
}

function toBorrowerBanking(payload: BackendBorrowerDetail): BorrowerDetail["borrower"]["banking"] {
  return {
    bank: payload.bankName ?? "",
    accountHolder: payload.accountHolderName ?? "",
    accountNumber: payload.bankAccountNumberMasked ?? "",
    ifsc: payload.ifscCode ?? "",
  };
}

function toBorrowerReferences(
  payload: BackendBorrowerDetail,
): BorrowerDetail["borrower"]["references"] {
  if (!payload.referencePersonName || !payload.referencePersonNumber) return [];
  return [{ name: payload.referencePersonName, contact: payload.referencePersonNumber }];
}

function toBorrower(payload: BackendBorrowerDetail): BorrowerDetail["borrower"] {
  return {
    id: payload.id,
    fullName: payload.fullName,
    pan: payload.pan ?? "",
    aadhaar: payload.aadharNumberMasked ?? "",
    mobile: payload.mobile ?? "",
    email: payload.email,
    dob: payload.dateOfBirth ?? "",
    gender: (payload.gender ?? "M") as "M" | "F" | "O",
    maritalStatus: (payload.maritalStatus ?? "SINGLE") as
      | "SINGLE"
      | "MARRIED"
      | "DIVORCED"
      | "WIDOWED",
    fathersName: payload.fatherName ?? "",
    spouseName: payload.spouseName,
    address: toBorrowerAddress(payload),
    employment: toBorrowerEmployment(payload),
    banking: toBorrowerBanking(payload),
    references: toBorrowerReferences(payload),
    kycComplete: !!payload.pan && !!payload.aadharNumberMasked,
    visibleLspIds: payload.visibleLspIds ?? [],
  } as BorrowerDetail["borrower"];
}

function backendToDetail(
  payload: BackendBorrowerDetail,
  lspNamesById: ReadonlyMap<string, string> = new Map(),
): BorrowerDetail {
  return {
    borrower: toBorrower(payload),
    visibleLsps: toVisibleLsps(payload, lspNamesById),
    totals: summarizeLoans(payload),
  };
}

// ─── Public surface ──────────────────────────────────────────────────────────

/** Fetch the joined borrower-detail payload for `/borrowers/:id`. */
export async function fetchBorrowerDetail(id: string): Promise<BorrowerDetail> {
  const payload = await requestJson<BackendBorrowerDetail>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}`,
  );
  const lspNamesById =
    payload.visibleLsps && payload.visibleLsps.length > 0
      ? new Map<string, string>()
      : new Map((await listLspOptions()).map((lsp) => [lsp.id, lsp.name]));
  return backendToDetail(payload, lspNamesById);
}
