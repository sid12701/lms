/**
 * View-layer types for the `/lsps` admin surface (Phase 9 — Agent A).
 *
 * `Lsp` and `LspWebhookSubscription` are the canonical wire shapes from
 * `src/schemas/lsp.ts`. The list page consumes a `LspRow` projection that
 * adds the operator-facing aggregate `userCount` so the table can show
 * tenant-user reach at a glance without a second round-trip.
 *
 * All mutations carry an idempotency key (BR-5). Webhook subscription writes
 * are upsert-style — there's one subscription per LSP per blueprint §11.
 */
import { z } from "zod";
import {
  Lsp,
  LspOperationalStatus,
  LspStatus,
  LspStatusChangeReason,
  LspWebhookSubscription,
  WebhookEventType,
} from "@/schemas/lsp";

export type { Lsp, LspWebhookSubscription };

/** Server-side filter shape for the list page. URL-bound via `useSearchParams`. */
const LspsListFilters = z.object({
  status: LspStatus.optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});
export type LspsListFilters = z.infer<typeof LspsListFilters>;

/** Row projection used by the list table. */
export interface LspRow extends Lsp {
  /** Number of app users scoped to this LSP (served by the directory endpoint). */
  userCount: number;
  /** Whether a webhook subscription exists + is enabled. */
  webhookEnabled: boolean;
}

export interface LspsListResponse {
  items: LspRow[];
  total: number;
  page: number;
  pageSize: number;
}

export interface LspIpAllowlistEntry {
  id: string;
  lspId: string;
  cidr: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LspAllowlistEnforcement {
  enforceUi: boolean;
  enforceApi: boolean;
}

export type LspIpAllowlistSurface = "ui" | "api";

/** Create-LSP form input (operator submits code + name; status defaults to ACTIVE). */
export interface CreateLspInput {
  code: string;
  name: string;
  idempotencyKey: string;
}

/** `PUT …/lsps/{id}/status` — reason and note are required by the backend. */
export interface UpdateLspStatusInput {
  status: z.infer<typeof LspOperationalStatus>;
  reason: z.infer<typeof LspStatusChangeReason>;
  note: string;
  idempotencyKey: string;
}

export interface LspAuditEventRow {
  id: string;
  lspId: string;
  action: string;
  actorUsername: string;
  reason: z.infer<typeof LspStatusChangeReason> | null;
  note: string | null;
  cascadedClientCount: number;
  correlationId: string | null;
  createdAt: string;
}

export interface LspMutationResponse {
  lsp: LspRow;
}

/** Webhook subscription upsert. `null` returned when none exists yet. */
export interface UpsertWebhookSubscriptionInput {
  enabled: boolean;
  endpointUrl: string;
  /** Empty string keeps the currently stored secret (write-only on reads). */
  signingSecret: string;
  eventTypes: z.infer<typeof WebhookEventType>[];
  idempotencyKey: string;
}

export interface WebhookSubscriptionResponse {
  subscription: LspWebhookSubscription | null;
}
