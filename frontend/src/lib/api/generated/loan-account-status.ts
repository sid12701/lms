/** This file was auto-generated from openapi/openapi.json. Do not edit manually. */
import type { components } from "./schema";

export const LOAN_ACCOUNT_STATUSES = [
  "PENDING_DISBURSEMENT",
  "DISBURSEMENT_REQUESTED",
  "DISBURSED",
  "INVALID",
  "CLOSED",
  "FORECLOSED",
  "DISBURSEMENT_FAILED",
  "DISBURSEMENT_PENDING_RECONCILIATION",
] as const;
export type LoanAccountStatus = components["schemas"]["LoanAccountStatus"];
