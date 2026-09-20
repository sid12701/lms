/**
 * API clients admin surface, wired to the live backend.
 *
 * Backend contract: `ApiClientAdminController` under
 * `/api/v1/internal/admin/api-clients` (SYSTEM_ADMIN only).
 *
 * Status enum is ACTIVE / INACTIVE on the backend; the frontend uses
 * ACTIVE / DISABLED. Translated in both directions.
 */
import { buildQueryPath, requestJson, requestJsonWithHeaders } from "@/lib/api/http-client";
import { readPaginationHeaders } from "@/lib/api/pagination-headers";
import type { ApiClient, ApiClientStatus } from "@/schemas/user";
import type {
  ApiClientMutationResponse,
  ApiClientRow,
  ApiClientsListFilters,
  ApiClientsListResponse,
  CreateApiClientInput,
  CreateApiClientResponse,
  RotateApiClientSecretInput,
  RotateApiClientSecretResponse,
  UpdateApiClientInput,
} from "./types";

const BASE = "/api/v1/internal/admin/api-clients";

interface BackendApiClientResponse {
  id: string;
  clientId: string;
  name: string;
  description: string | null;
  status: string;
  lspId: string;
  lspName: string;
  createdAt: string;
  lastUsedAt: string | null;
  lastRotatedAt?: string | null;
}

interface BackendCreatedApiClientResponse extends BackendApiClientResponse {
  clientSecret: string;
}

interface BackendRotateSecretResponse {
  clientId: string;
  clientSecret: string;
  oldSecretValidUntil: string | null;
}

function frontendStatus(value: string): ApiClientStatus {
  return value === "INACTIVE" ? "DISABLED" : "ACTIVE";
}

function toApiClient(payload: BackendApiClientResponse): ApiClient {
  return {
    id: payload.id,
    clientId: payload.clientId,
    name: payload.name,
    lspId: payload.lspId,
    status: frontendStatus(payload.status),
    createdAt: payload.createdAt,
    lastUsedAt: payload.lastUsedAt,
    lastRotatedAt: payload.lastRotatedAt ?? null,
    ipAllowList: [],
  };
}

function toRow(payload: BackendApiClientResponse): ApiClientRow {
  const client = toApiClient(payload);
  return {
    ...client,
    lspName: payload.lspName,
    ipAllowlistCount: 0,
  };
}

/**
 * M20 — the directory is server-paginated and server-filtered. Every filter
 * (status, LSP, text) travels to the backend, which applies it to the full
 * dataset before paginating; the total comes from the pagination headers.
 * Filtering/paginating only a fetched slice locally hid matching clients on
 * other pages and understated totals, so the local path is gone.
 */
export async function listApiClients(
  filters: ApiClientsListFilters = {},
  signal?: AbortSignal,
): Promise<ApiClientsListResponse> {
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;
  const path = buildQueryPath(BASE, {
    status: filters.status ? (filters.status === "DISABLED" ? "INACTIVE" : "ACTIVE") : undefined,
    lspId: filters.lspId,
    q: filters.q,
    offset: page * pageSize,
    limit: pageSize,
    paginationDetails: "ON",
  });
  const { data, headers } = await requestJsonWithHeaders<BackendApiClientResponse[]>(path, {
    signal,
  });
  const pagination = readPaginationHeaders(headers);
  return {
    items: data.map(toRow),
    total: pagination.totalCount ?? data.length,
    page,
    pageSize,
  };
}

export async function createApiClient(
  input: CreateApiClientInput,
): Promise<CreateApiClientResponse> {
  const payload = await requestJson<BackendCreatedApiClientResponse>(
    BASE,
    {
      method: "POST",
      body: JSON.stringify({
        name: input.name,
        description: null,
        lspId: input.lspId,
        status: "ACTIVE",
      }),
    },
    { idempotencyKey: input.idempotencyKey },
  );
  const row = toRow(payload);
  return { client: row, clientSecret: payload.clientSecret };
}

/**
 * Updates an API client's name and/or status via `PUT /api-clients/{id}`.
 *
 * Status is translated to the backend vocabulary (frontend DISABLED → backend
 * INACTIVE). Fields left undefined are sent as null, which the backend treats
 * as "leave unchanged".
 */
export async function updateApiClient(
  id: string,
  input: UpdateApiClientInput,
): Promise<ApiClientMutationResponse> {
  const payload = await requestJson<BackendApiClientResponse>(
    `${BASE}/${id}`,
    {
      method: "PUT",
      body: JSON.stringify({
        name: input.name ?? null,
        status: input.status ? (input.status === "DISABLED" ? "INACTIVE" : "ACTIVE") : null,
      }),
    },
    { idempotencyKey: input.idempotencyKey },
  );
  return { client: toRow(payload) };
}

export async function rotateApiClientSecret(
  id: string,
  _input: RotateApiClientSecretInput,
): Promise<RotateApiClientSecretResponse> {
  const payload = await requestJson<BackendRotateSecretResponse>(
    `${BASE}/${id}/rotate-secret`,
    {
      method: "POST",
      body: JSON.stringify({ graceSeconds: 300 }),
    },
    { idempotencyKey: _input.idempotencyKey },
  );

  return { clientSecret: payload.clientSecret };
}
