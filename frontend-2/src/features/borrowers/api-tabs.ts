/**
 * Per-tab API client for the borrower-detail surface.
 *
 * The Loans tab is wired to the same backend endpoint as the borrower
 * detail (`/api/v1/internal/admin/borrowers/{id}`), projecting only the
 * `loans[]` array from the response. The Activity tab stays on the mock
 * router because the backend doesn't expose a borrower-scoped audit
 * stream (status transitions / PII reveals / document accesses are
 * per-application, not per-borrower).
 */
import { z } from "zod";
import { ApiError, requestJson } from "@/lib/api/http-client";
import { dispatch } from "@/mocks/router";
import { loadStoredSession } from "@/lib/api/session-storage";
import { mapBackendStatus } from "@/features/loan-applications/api";
import type {
  BorrowerActivityResponse,
  BorrowerLoanRow,
  BorrowerLoansResponse,
} from "./types";

const BACKEND_BASE = "/api/v1/internal/admin/borrowers";

function isInternalSession(): boolean {
  const role = loadStoredSession()?.user.role;
  return role === "SYSTEM_ADMIN" || role === "OPS_USER";
}

const Permissive = z.unknown();

const BorrowerLoansResponseSchema: z.ZodType<BorrowerLoansResponse> = z.object({
  loans: z.array(Permissive).readonly(),
}) as unknown as z.ZodType<BorrowerLoansResponse>;

const BorrowerActivityResponseSchema: z.ZodType<BorrowerActivityResponse> = z.object({
  entries: z.array(Permissive).readonly(),
}) as unknown as z.ZodType<BorrowerActivityResponse>;

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

interface BackendBorrowerDetailLoans {
  loans: BackendBorrowerLoanRow[];
}

function toNumber(value: number | string | null | undefined): number {
  if (value == null) return 0;
  const parsed = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(parsed) ? parsed : 0;
}

function toLoanRow(row: BackendBorrowerLoanRow): BorrowerLoanRow {
  return {
    applicationId: row.applicationId ?? row.loanAccountId ?? "",
    externalLoanId: row.accountNumber,
    lspId: row.lspId ?? "",
    lspName: row.lspName ?? row.lspCode ?? "",
    productId: row.loanProductCode ?? "",
    productName: row.loanProductCode ?? "",
    requestedAmount: toNumber(row.principalAmount),
    tenureMonths: row.tenureMonths,
    status: mapBackendStatus(row.status) as BorrowerLoanRow["status"],
    createdAt: row.createdAt,
    updatedAt: row.closedAt ?? row.disbursedAt ?? row.approvedAt ?? row.createdAt,
  };
}

/** GET `/api/v1/borrowers/:id/loans` — Loans tab data. */
export async function fetchBorrowerLoans(id: string): Promise<BorrowerLoansResponse> {
  if (isInternalSession()) {
    try {
      const payload = await requestJson<BackendBorrowerDetailLoans>(
        `${BACKEND_BASE}/${encodeURIComponent(id)}`,
      );
      return { loans: (payload.loans ?? []).map(toLoanRow) };
    } catch (error) {
      if (!(error instanceof ApiError) || error.status >= 500) throw error;
    }
  }

  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${encodeURIComponent(id)}/loans`,
    },
    BorrowerLoansResponseSchema,
  );
}

/** GET `/api/v1/borrowers/:id/activity` — Activity tab data.
 *
 * No backend equivalent — the audit streams are per-application, not
 * per-borrower. Stays on the mock router until a borrower-scoped audit
 * endpoint ships. See docs/INTEGRATION-STATUS.md.
 */
export async function fetchBorrowerActivity(id: string): Promise<BorrowerActivityResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${encodeURIComponent(id)}/activity`,
    },
    BorrowerActivityResponseSchema,
  );
}
