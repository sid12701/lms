/**
 * Shared contract for the Phase 4 home dashboard.
 *
 * `features/home/components/*` consume these shapes and `features/home/page.tsx`
 * wires them via a TanStack Query hook.
 *
 * Types intentionally use plain TS (not Zod-inferred) — these are
 * presentation projections, not domain entities.
 */
import type { LoanStatus } from "@/types";
import type { AlertSeverity, AlertSubjectType } from "@/schemas/alert";
import type { DelinquencyBucket } from "@/schemas/loan-account";

/**
 * One row of the "Applications by status" chart on the internal home.
 * `count` is the number of applications currently in `status`.
 */
export interface ApplicationsByStatusBucket {
  status: LoanStatus;
  count: number;
}

/**
 * One row of the "Loans by DPD bucket" chart on the internal home.
 * `count` is the number of loan accounts whose next-due installment is in
 * `bucket`. Computed from live loan-account delinquency data.
 */
export interface DpdBucketSummary {
  bucket: DelinquencyBucket;
  count: number;
}

/**
 * One row of the "Recent applications" table on either home variant.
 * Borrower name is the *masked* display form (e.g. "A•••a Devi") — the
 * raw name is never sent to the home page; reveal goes through the PII
 * dialog on the detail surface.
 */
export interface HomeRecentApplication {
  id: string;
  externalLoanId: string | null;
  borrowerNameMasked: string;
  lspName: string;
  productName: string;
  status: LoanStatus;
  requestedAmount: number;
  createdAt: string;
}

/**
 * One row of the "Open alerts" feed on either home variant.
 */
export interface HomeAlertSummary {
  id: string;
  severity: AlertSeverity;
  title: string;
  /**
   * The rule's detail sentence — the only field that separates a run of
   * same-titled alerts (loan id, DPD, overdue amount). `null` when the alert
   * carries none, so the card omits the line rather than rendering an empty one.
   */
  message: string | null;
  subjectType: AlertSubjectType;
  subjectId: string;
  createdAt: string;
}

/**
 * Internal-user dashboard payload (SYSTEM_ADMIN, OPS_USER, PRODUCT_ADMIN).
 * Aggregates across every LSP the role can see.
 */
export interface InternalHomeKpis {
  applicationsAwaitingApproval: number;
  applicationsInDisbursement: number;
  /** ₹ total principal disbursed across the portfolio snapshot (lifetime, not MTD). */
  totalDisbursedAmount: number;
  overdueLoansCount: number;
  /** ₹ outstanding across all overdue installments. */
  overdueAmount: number;
  applicationsByStatus: readonly ApplicationsByStatusBucket[];
  /** Loans grouped by delinquency bucket — drives the DPD bar chart on /home. */
  dpdBuckets: readonly DpdBucketSummary[];
  recentApplications: readonly HomeRecentApplication[];
  openAlerts: readonly HomeAlertSummary[];
  /**
   * When the portfolio snapshot these figures come from was computed.
   * `null` means no snapshot exists yet — which is not the same as zero, and
   * must not be rendered as one.
   */
  dataAsOf: string | null;
}

/** Narrow union returned by the home API based on the caller's role. */
export type HomeKpis = { kind: "internal"; data: InternalHomeKpis };
