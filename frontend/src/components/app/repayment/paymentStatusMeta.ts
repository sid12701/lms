import type { Intent } from "@/lib/lifecycle";

/**
 * Settlement states for a payment row.
 *
 * Two vocabularies land here on purpose. `POSTED` is the ops repayments
 * ledger's own word for "we recorded this payment"; the other three are the
 * backend's `LoanPaymentStatus`, which is what the LSP payments endpoint
 * returns verbatim. Keeping both in one map is what lets the partner ledger and
 * the ops ledger read the same rather than one of them showing raw enums.
 */
export type PaymentSettlementStatus = "POSTED" | "RECEIVED" | "PENDING_RECONCILIATION" | "FAILED";

export interface PaymentStatusMeta {
  label: string;
  intent: Intent;
}

const PAYMENT_STATUS_META: Record<PaymentSettlementStatus, PaymentStatusMeta> = {
  POSTED: { label: "Posted", intent: "success" },
  RECEIVED: { label: "Received", intent: "success" },
  PENDING_RECONCILIATION: { label: "Pending reconciliation", intent: "warning" },
  FAILED: { label: "Failed", intent: "danger" },
};

export function resolvePaymentStatusMeta(status: string): PaymentStatusMeta {
  const trimmed = status.trim();
  const known = PAYMENT_STATUS_META[trimmed as PaymentSettlementStatus];
  if (known) return known;
  return {
    label: trimmed ? `Unknown (${trimmed})` : "Unknown",
    intent: "neutral",
  };
}
