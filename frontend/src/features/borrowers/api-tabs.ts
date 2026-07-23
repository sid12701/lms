/**
 * Per-tab API client for the borrower-detail surface.
 *
 * The Loans tab is wired to the same backend endpoint as the borrower
 * detail (`/api/v1/internal/admin/borrowers/{id}`), projecting only the
 * `loans[]` array from the response.
 */
import { requestJson } from "@/lib/api/http-client";
import { apiLoanStatus } from "@/lib/loan-application-status";
import { finiteNumberOrZero as toNumber } from "@/lib/number";
import type { BorrowerLoanRow, BorrowerLoansResponse } from "./types";

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

interface BackendBorrowerDetailLoans {
  loans: BackendBorrowerLoanRow[];
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
    status: apiLoanStatus(row.status) as BorrowerLoanRow["status"],
    createdAt: row.createdAt,
    updatedAt: row.closedAt ?? row.disbursedAt ?? row.approvedAt ?? row.createdAt,
  };
}

/** GET `/api/v1/borrowers/:id/loans` — Loans tab data. */
export async function fetchBorrowerLoans(id: string): Promise<BorrowerLoansResponse> {
  const payload = await requestJson<BackendBorrowerDetailLoans>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}`,
  );
  return { loans: (payload.loans ?? []).map(toLoanRow) };
}
