/**
 * View-layer types for the `/api-clients` admin surface (Phase 9 — Agent D).
 *
 * `ApiClient` is the canonical wire shape from `src/schemas/user.ts`. The row
 * projection adds the LSP name (resolved from `db.lsps`).
 *
 * Create and rotate mutations both return a one-time `clientSecret` field —
 * surfaced exactly once via `ApiSecretReveal`. Subsequent reads return only
 * the non-secret metadata (handled by `ApiClientSecretMeta`).
 *
 * IP allow-lists are managed per LSP (UI vs API surfaces) on the LSPs admin
 * page — not per client. The per-client `ipAllowList` fields below are retained
 * only for backward compatibility and are not sent by the current UI.
 *
 * All mutations are SYSTEM_ADMIN-only and carry an idempotency key (BR-5);
 * create and rotate are idempotent server-side on that key.
 */
import { z } from "zod";
import { ApiClient, ApiClientStatus } from "@/schemas/user";

export type { ApiClient };

/** Server-side filter shape for the list page. */
const ApiClientsListFilters = z.object({
  status: ApiClientStatus.optional(),
  lspId: z.string().uuid().optional(),
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});
export type ApiClientsListFilters = z.infer<typeof ApiClientsListFilters>;

export interface ApiClientRow extends ApiClient {
  /** Resolved from `db.lsps`. */
  lspName: string;
  /**
   * Retained for the create/reveal cards. Always 0 today: IP allow-lists moved
   * to the per-LSP surfaces, so clients no longer carry their own CIDR list.
   */
  ipAllowlistCount: number;
}

export interface ApiClientsListResponse {
  items: ApiClientRow[];
  total: number;
  page: number;
  pageSize: number;
}

/**
 * Create-client input. The server mints a `clientId` + secret and returns
 * both in `CreateApiClientResponse`. The cleartext secret is shown exactly
 * once via `ApiSecretReveal`.
 */
export interface CreateApiClientInput {
  name: string;
  lspId: string;
  /** @deprecated Per-client allowlists removed — use LSP surface allowlists. Backend only. */
  ipAllowList?: string[];
  idempotencyKey: string;
}

export interface CreateApiClientResponse {
  client: ApiClientRow;
  /** Cleartext secret — shown exactly once, never persisted in cleartext. */
  clientSecret: string;
}

export interface UpdateApiClientInput {
  name?: string;
  status?: z.infer<typeof ApiClientStatus>;
  /** @deprecated Backend only. */
  ipAllowList?: string[];
  idempotencyKey: string;
}

export interface ApiClientMutationResponse {
  client: ApiClientRow;
}

/** Rotate-secret: returns a brand-new cleartext secret + updates `lastRotatedAt`. */
export interface RotateApiClientSecretInput {
  reason: string;
  idempotencyKey: string;
}

export interface RotateApiClientSecretResponse {
  client: ApiClientRow;
  clientSecret: string;
}
