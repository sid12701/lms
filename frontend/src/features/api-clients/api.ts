/**
 * API clients admin surface, wired to the live backend.
 *
 * Backend contract: `ApiClientAdminController` under
 * `/api/v1/internal/admin/api-clients` (SYSTEM_ADMIN only).
 *
 * Status enum is ACTIVE / INACTIVE on the backend; the frontend uses
 * ACTIVE / DISABLED. Translated in both directions.
 */
import { requestJson } from "@/lib/api/http-client";
import type { ApiClient, ApiClientStatus } from "@/schemas/user";
import type {
  ApiClientRow,
  ApiClientsListFilters,
  ApiClientsListResponse,
  CreateApiClientInput,
  CreateApiClientResponse,
  RotateApiClientSecretInput,
  RotateApiClientSecretResponse,
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

const lastRotatedAtById = new Map<string, string>();

export async function listApiClients(
  filters: ApiClientsListFilters = {},
): Promise<ApiClientsListResponse> {
  const all = await requestJson<BackendApiClientResponse[]>(BASE);
  const filtered = all.filter((row) => {
    if (filters.status && frontendStatus(row.status) !== filters.status) return false;
    if (filters.lspId && row.lspId !== filters.lspId) return false;
    if (filters.q) {
      const needle = filters.q.toLowerCase();
      if (
        !row.name.toLowerCase().includes(needle) &&
        !row.clientId.toLowerCase().includes(needle) &&
        !(row.description ?? "").toLowerCase().includes(needle)
      ) {
        return false;
      }
    }
    return true;
  });
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 20;
  const items = filtered.slice(page * pageSize, page * pageSize + pageSize).map(toRow);
  return { items, total: filtered.length, page, pageSize };
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

  const list = await requestJson<BackendApiClientResponse[]>(BASE);
  const found = list.find((row) => row.id === id);
  if (!found) throw new Error(`API client ${id} not found`);
  const row = toRow(found);
  if (payload.oldSecretValidUntil) {
    lastRotatedAtById.set(id, payload.oldSecretValidUntil);
  } else {
    lastRotatedAtById.set(id, new Date().toISOString());
  }
  return { client: row, clientSecret: payload.clientSecret };
}

export function getApiClientLastRotatedAt(id: string): string | null {
  return lastRotatedAtById.get(id) ?? null;
}
