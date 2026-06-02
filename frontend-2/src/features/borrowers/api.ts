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
import { z } from "zod";
import { requestJson } from "@/lib/api/http-client";
import { dispatch } from "@/mocks/router";
import { loadStoredSession } from "@/lib/api/session-storage";
import type { BorrowerDetail } from "./types";

const BACKEND_BASE = "/api/v1/internal/admin/borrowers";

function isInternalSession(): boolean {
  const role = loadStoredSession()?.user.role;
  return role === "SYSTEM_ADMIN" || role === "OPS_USER";
}

// ─── Runtime parsers (mock fallback) ─────────────────────────────────────────

const Permissive = z.unknown();

const VisibleLspSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
});

export const BorrowerDetailSchema = z.object({
  borrower: Permissive,
  visibleLsps: z.array(VisibleLspSchema).readonly(),
  totals: z.object({
    openApplicationsCount: z.number().int().nonnegative(),
    closedApplicationsCount: z.number().int().nonnegative(),
    lifetimeDisbursedAmount: z.number().nonnegative(),
    activeOverdueAmount: z.number().nonnegative(),
  }),
}) as unknown as z.ZodType<BorrowerDetail>;

// ─── Backend response shape ──────────────────────────────────────────────────

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

function toNumber(value: number | string | null | undefined): number {
  if (value == null) return 0;
  const parsed = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(parsed) ? parsed : 0;
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

function backendToDetail(payload: BackendBorrowerDetail): BorrowerDetail {
  // Project visibleLsps from the loans collection (the backend gives us
  // only ids on the borrower; loan rows carry the human-readable name).
  const visibleLsps = (() => {
    const map = new Map<string, string>();
    for (const loan of payload.loans) {
      if (loan.lspId && loan.lspName) map.set(loan.lspId, loan.lspName);
    }
    // Backfill any ids that don't have a loan-row name yet.
    for (const id of payload.visibleLspIds ?? []) {
      if (!map.has(id)) map.set(id, id.slice(0, 8));
    }
    return Array.from(map.entries()).map(([id, name]) => ({ id, name }));
  })();

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

  const borrower = {
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
    address: {
      residential: [payload.addressLine1, payload.addressLine2].filter(Boolean).join(", ") || "",
      city: payload.city ?? "",
      state: payload.state ?? "",
      zip: payload.addressZipCode ?? "",
    },
    employment: {
      type: (payload.employmentType ?? "SALARIED") as
        | "SALARIED"
        | "SELF_EMPLOYED"
        | "BUSINESS"
        | "RETIRED"
        | "STUDENT"
        | "UNEMPLOYED",
      organization: payload.organizationName,
      employeeId: payload.employeeId,
      location:
        [payload.employmentCity, payload.employmentState].filter(Boolean).join(", ") || null,
      monthlyIncome: toNumber(payload.monthlyIncome),
      annualIncome: toNumber(payload.annualIncome),
    },
    banking: {
      bank: payload.bankName ?? "",
      accountHolder: payload.accountHolderName ?? "",
      accountNumber: payload.bankAccountNumberMasked ?? "",
      ifsc: payload.ifscCode ?? "",
    },
    references:
      payload.referencePersonName && payload.referencePersonNumber
        ? [
            {
              name: payload.referencePersonName,
              contact: payload.referencePersonNumber,
            },
          ]
        : [],
    kycComplete: !!payload.pan && !!payload.aadharNumberMasked,
    visibleLspIds: payload.visibleLspIds ?? [],
  };

  return {
    borrower: borrower as unknown as BorrowerDetail["borrower"],
    visibleLsps,
    totals: {
      openApplicationsCount: openCount,
      closedApplicationsCount: closedCount,
      lifetimeDisbursedAmount: lifetimeDisbursed,
      // Gap #6: server-authoritative aggregate across the borrower's
      // active loans. Drives the Profile tab "active overdue" tile and
      // the OverviewTab "active overdue" row.
      activeOverdueAmount: toNumber(payload.delinquency?.activeOverdueAmount ?? 0),
    },
  };
}

// ─── Public surface ──────────────────────────────────────────────────────────

/** Fetch the joined borrower-detail payload for `/borrowers/:id`. */
export async function fetchBorrowerDetail(id: string): Promise<BorrowerDetail> {
  if (isInternalSession()) {
    const payload = await requestJson<BackendBorrowerDetail>(
      `${BACKEND_BASE}/${encodeURIComponent(id)}`,
    );
    return backendToDetail(payload);
  }

  // LSP-role: mock-router demo path — real LSP read API is a future workstream (#78).
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${encodeURIComponent(id)}`,
    },
    BorrowerDetailSchema,
  );
}
