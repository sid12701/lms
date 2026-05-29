/**
 * Shared contract for the Phase 6 borrower-detail surface.
 *
 * The mock API returns these shapes; per-tab components consume them.
 * Per the Phase 4 lesson (see `docs/CURRENT-STATE.md` §9), `LoanStatus` and
 * any other Zod enum value MUST be imported from its schema source, not
 * from `@/types` (type-only re-exports crash at runtime).
 */
import { z } from "zod";
import type { LoanApplication, Lsp, LoanProduct } from "@/types";
import type {
  Borrower,
  BorrowerBanking,
  BorrowerEmployment,
  BorrowerReference,
} from "@/schemas/borrower";

// ─── Tabs ────────────────────────────────────────────────────────────────────

export const BorrowerDetailTab = z.enum(["profile", "loans"]);
export type BorrowerDetailTab = z.infer<typeof BorrowerDetailTab>;

// ─── Detail surface ──────────────────────────────────────────────────────────

/**
 * Joined borrower-detail payload. Includes the canonical `Borrower` plus the
 * names of every LSP that can see this borrower (`visibleLspIds` resolved to
 * `visibleLsps`) so the Profile tab doesn't run N look-ups per render.
 */
export interface BorrowerDetail {
  borrower: Borrower;
  /** Resolved labels for every id in `borrower.visibleLspIds`. */
  visibleLsps: readonly { id: string; name: string }[];
  /** Tally for the Profile tab's at-a-glance card. */
  totals: {
    openApplicationsCount: number;
    closedApplicationsCount: number;
    lifetimeDisbursedAmount: number;
    activeOverdueAmount: number;
  };
}

// ─── Loans tab ───────────────────────────────────────────────────────────────

/**
 * Row of the Loans tab — every application this borrower has had with any
 * LSP. Lighter than the triage `LoanApplicationListItem` because the borrower
 * column is implicit and the row links back to the application's detail.
 */
export interface BorrowerLoanRow {
  applicationId: string;
  externalLoanId: string | null;
  lspId: string;
  lspName: string;
  productId: string;
  productName: string;
  requestedAmount: number;
  tenureMonths: number;
  status: LoanApplication["status"];
  createdAt: string;
  updatedAt: string;
}

export interface BorrowerLoansResponse {
  loans: readonly BorrowerLoanRow[];
}

// ─── Mutation contracts ──────────────────────────────────────────────────────

/**
 * Recorded when an operator opens / previews / downloads a document.
 * `action` mirrors `DocumentAccessAction` from `src/schemas/audit.ts`.
 */
export interface RecordDocumentAccessInput {
  documentId: string;
  action: "VIEW" | "PREVIEW" | "DOWNLOAD";
  idempotencyKey: string;
}

export interface RecordDocumentAccessResponse {
  auditId: string;
}

// ─── Re-exports — convenience for tab components ─────────────────────────────

export type { Borrower, BorrowerBanking, BorrowerEmployment, BorrowerReference, Lsp, LoanProduct };
