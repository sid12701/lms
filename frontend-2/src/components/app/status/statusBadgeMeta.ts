import { STATUS_META, type Intent } from "@/lib/lifecycle";
import type { LoanAccountStatus, LoanStatus } from "@/types";

export type AnyStatus = LoanStatus | LoanAccountStatus;

export interface ResolvedStatusMeta {
  label: string;
  intent: Intent;
}

const ACCOUNT_STATUS_META: Record<LoanAccountStatus, ResolvedStatusMeta> = {
  PENDING_DISBURSEMENT: { label: "Pending disbursement", intent: "progress" },
  ACTIVE: { label: "Active", intent: "success" },
  CLOSED: { label: "Closed", intent: "neutral" },
  FORECLOSED: { label: "Foreclosed", intent: "neutral" },
};

/** Lookup that succeeds for either enum. Always returns a non-empty meta. */
export function resolveStatusMeta(status: AnyStatus): ResolvedStatusMeta {
  if (status in STATUS_META) {
    const m = STATUS_META[status as LoanStatus];
    return { label: m.label, intent: m.intent };
  }
  if (status in ACCOUNT_STATUS_META) {
    return ACCOUNT_STATUS_META[status as LoanAccountStatus];
  }
  return { label: String(status), intent: "neutral" };
}
