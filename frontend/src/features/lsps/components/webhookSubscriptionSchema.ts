/**
 * Webhook subscription form schema (sibling to LspWebhookSubscriptionDialog).
 *
 * Mirrors `LspWebhookSubscription` so form validation matches the wire
 * contract exactly. Toggle (`enabled`) lives in the form too so disabling
 * does not require re-typing the secret.
 */
import { z } from "zod";
import { LspWebhookSubscription, WebhookEventType } from "@/schemas/lsp";

export const WebhookSubscriptionFormSchema = z.object({
  enabled: LspWebhookSubscription.shape.enabled,
  endpointUrl: LspWebhookSubscription.shape.endpointUrl,
  /**
   * The backend never returns the stored secret (write-only), so the form
   * accepts blank ("keep the current secret") or a full 32-256 char value.
   */
  signingSecret: z
    .string()
    .max(256)
    .refine((value) => value === "" || value.length >= 32, {
      message: "Secret must be 32-256 characters, or blank to keep the current one.",
    }),
  eventTypes: z.array(WebhookEventType).min(1, "Select at least one event type."),
});

export type WebhookSubscriptionFormValues = z.infer<typeof WebhookSubscriptionFormSchema>;

export const ALL_WEBHOOK_EVENT_TYPES = WebhookEventType.options;
