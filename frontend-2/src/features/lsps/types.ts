/**
 * View-layer types for the `/lsps` admin surface (Phase 9 — Agent A).
 *
 * `Lsp` and `LspWebhookSubscription` are the canonical wire shapes from
 * `src/schemas/lsp.ts`. The list page consumes a `LspRow` projection that
 * adds the operator-facing aggregate `apiClientCount` so the table can show
 * "credentials" at a glance without a second round-trip.
 *
 * All mutations carry an idempotency key (BR-5). Webhook subscription writes
 * are upsert-style — there's one subscription per LSP per blueprint §11.
 */
import { z } from "zod";
import { Lsp, LspStatus, LspWebhookSubscription, WebhookEventType } from "@/schemas/lsp";

export type { Lsp, LspWebhookSubscription };
export { LspStatus, WebhookEventType };

/** Server-side filter shape for the list page. URL-bound via `useSearchParams`. */
export const LspsListFilters = z.object({
  status: LspStatus.optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});
export type LspsListFilters = z.infer<typeof LspsListFilters>;

/** Row projection used by the list table. */
export interface LspRow extends Lsp {
  /** Number of API clients registered to this LSP. */
  apiClientCount: number;
  /** Whether a webhook subscription exists + is enabled. */
  webhookEnabled: boolean;
}

export interface LspsListResponse {
  items: LspRow[];
  total: number;
  page: number;
  pageSize: number;
}

/** Create-LSP form input (operator submits code + name; status defaults to ACTIVE). */
export interface CreateLspInput {
  code: string;
  name: string;
  idempotencyKey: string;
}

/** Update-LSP form input. Status changes go through the same path. */
export interface UpdateLspInput {
  name?: string;
  status?: z.infer<typeof LspStatus>;
  idempotencyKey: string;
}

export interface LspMutationResponse {
  lsp: LspRow;
}

/** Webhook subscription upsert. `null` returned when none exists yet. */
export interface UpsertWebhookSubscriptionInput {
  enabled: boolean;
  endpointUrl: string;
  signingSecret: string;
  eventTypes: z.infer<typeof WebhookEventType>[];
  idempotencyKey: string;
}

export interface WebhookSubscriptionResponse {
  subscription: LspWebhookSubscription | null;
}
