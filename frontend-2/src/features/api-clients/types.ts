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
 * IP allow-list is per-client (BR-8). The editor accepts a list of IPv4 / CIDR
 * entries; the server-side handler validates each against `IpCidr` from
 * `src/schemas/common.ts`.
 *
 * All mutations are SYSTEM_ADMIN-only and carry an idempotency key (BR-5).
 */
import { z } from "zod";
import { ApiClient, ApiClientStatus } from "@/schemas/user";

export type { ApiClient };
export { ApiClientStatus };

/** Server-side filter shape for the list page. */
export const ApiClientsListFilters = z.object({
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
  /** Number of CIDR ranges in `ipAllowList`. */
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
  /** @deprecated Per-client allowlists removed — use LSP surface allowlists. Mock router only. */
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
  /** @deprecated Mock router only. */
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

/**
 * Non-secret metadata required by `ApiClientSecretMeta` — the wire shape
 * doesn't carry `lastRotatedAt` today (the schema only has `lastUsedAt`).
 * For Phase 9 we add `lastRotatedAt` as a server-side derived field; until
 * the real backend exposes it, it is sourced from the most recent product
 * audit-style entry or stays null when the client has never been rotated.
 */
export interface ApiClientSecretMetadata {
  keyIdPrefix: string;
  createdAt: string;
  lastRotatedAt: string | null;
  ipAllowlistCount: number;
}
