/**
 * LSP (Lending Service Provider) tenant + webhook subscription.
 * Multi-tenancy carrier per blueprint §5; webhook contract per §11.
 */
import { z } from "zod";
import { Iso8601, Uuid } from "./common";

export const LspStatus = z.enum(["ACTIVE", "SUSPENDED", "INACTIVE"]);
export type LspStatus = z.infer<typeof LspStatus>;

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

/** Webhook event types per blueprint §11. */
export const WebhookEventType = z.enum([
  "loan.created",
  "loan.status.changed",
  "loan.disbursement.completed",
  "loan.disbursement.failed",
  "loan.repayment.posted",
  "loan.foreclosure.quote.generated",
  "loan.foreclosed",
]);
export type WebhookEventType = z.infer<typeof WebhookEventType>;

export const LspWebhookSubscription = z.object({
  lspId: Uuid,
  enabled: z.boolean(),
  endpointUrl: z
    .string()
    .url("endpoint must be a valid URL")
    .startsWith("https://", "endpoint must use https"),
  /** Min 32 chars to enforce secure HMAC signing per blueprint §11. */
  signingSecret: z.string().min(32).max(256),
  eventTypes: z.array(WebhookEventType).min(1, "at least one event"),
});
export type LspWebhookSubscription = z.infer<typeof LspWebhookSubscription>;
