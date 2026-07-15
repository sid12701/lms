/**
 * LSP (Lending Service Provider) tenant + webhook subscription.
 * Multi-tenancy carrier per blueprint §5; webhook contract per §11.
 */
import { z } from "zod";
import { Iso8601, Uuid } from "./common";

export const LspStatus = z.enum(["ACTIVE", "SUSPENDED", "INACTIVE"]);
export type LspStatus = z.infer<typeof LspStatus>;

/** Matches `LspStatusChangeReason` on the backend status API. */
export const LspStatusChangeReason = z.enum([
  "SECURITY_INCIDENT",
  "COMPLIANCE",
  "OFFBOARDING",
  "OPERATIONAL",
]);
export type LspStatusChangeReason = z.infer<typeof LspStatusChangeReason>;

/** Status values accepted by `PUT …/lsps/{id}/status`. */
export const LspOperationalStatus = z.enum(["ACTIVE", "INACTIVE"]);
export type LspOperationalStatus = z.infer<typeof LspOperationalStatus>;

export const Lsp = z.object({
  id: Uuid,
  code: z
    .string()
    .min(2)
    .max(16)
    .regex(/^[A-Z][A-Z0-9_-]*$/u, "code must be uppercase alnum"),
  name: z.string().min(1).max(120),
  status: LspStatus,
  createdAt: Iso8601,
});
export type Lsp = z.infer<typeof Lsp>;

/**
 * Webhook event types the backend actually emits, in loan-lifecycle order.
 *
 * Each value maps to a WebhookEventType enum constant with a live producer
 * (see FRONTEND_TO_BACKEND_EVENT in features/lsps/api.ts). The backend enum's
 * LOAN_DISBURSEMENT_UPDATED is intentionally omitted: no producer emits it.
 */
export const WebhookEventType = z.enum([
  "loan.created",
  "loan.status.changed",
  "loan.documents.uploaded",
  "loan.disbursement.requested",
  "loan.disbursement.completed",
  "loan.disbursement.failed",
  "loan.repayment.posted",
  "loan.repaid",
  "loan.foreclosure.quote.requested",
  "loan.foreclosed",
  "borrower.bank.details.updated",
]);
export type WebhookEventType = z.infer<typeof WebhookEventType>;

export const LspWebhookSubscription = z.object({
  lspId: Uuid,
  enabled: z.boolean(),
  endpointUrl: z
    .string()
    .url("endpoint must be a valid URL")
    .startsWith("https://", "endpoint must use https"),
  /**
   * Write-only on the backend: reads always return `null` plus `secretSet`.
   * Min 32 chars (when supplied) to enforce secure HMAC signing per blueprint §11.
   */
  signingSecret: z.string().min(32).max(256).nullable(),
  /** Whether a signing secret is configured server-side. */
  secretSet: z.boolean(),
  eventTypes: z.array(WebhookEventType).min(1, "at least one event"),
});
export type LspWebhookSubscription = z.infer<typeof LspWebhookSubscription>;
