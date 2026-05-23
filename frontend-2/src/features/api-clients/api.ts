/**
 * API clients admin surface, wired to the live backend.
 *
 * Backend contract: `ApiClientAdminController` under
 * `/api/v1/internal/admin/api-clients` (SYSTEM_ADMIN only).
 *
 * Backend gaps vs. the frontend projection (see docs/INTEGRATION-STATUS.md):
 *   - No PUT /{id} update endpoint — `updateApiClient` keeps the change
 *     in the page-session projection but cannot persist it.
 *   - No POST /{id}/rotate-secret — `rotateApiClientSecret` returns a
 *     locally-minted secret string so the reveal UI still functions but
 *     the backend's stored secret is unchanged.
 *   - The backend response does not surface the IP allow-list; we default
 *     to an empty array.
 *   - Status enum is ACTIVE / INACTIVE on the backend; the frontend uses
 *     ACTIVE / DISABLED. Translated in both directions.
 */
import { requestJson } from "@/lib/api/http-client";
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
}

interface BackendCreatedApiClientResponse extends BackendApiClientResponse {
  clientSecret: string;
}

function backendStatus(value: ApiClientStatus | undefined): string {
  return value === "DISABLED" ? "INACTIVE" : "ACTIVE";
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
  return { ...client, lspName: payload.lspName, ipAllowlistCount: 0 };
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
  return { client: { ...row, ipAllowList: input.ipAllowList, ipAllowlistCount: input.ipAllowList.length }, clientSecret: payload.clientSecret };
}

export async function updateApiClient(
  id: string,
  input: UpdateApiClientInput,
): Promise<ApiClientMutationResponse> {
  const all = await requestJson<BackendApiClientResponse[]>(BASE);
  const found = all.find((row) => row.id === id);
  if (!found) throw new Error(`API client ${id} not found`);
  const row = toRow(found);
  if (input.name) row.name = input.name;
  if (input.status) row.status = input.status;
  if (input.ipAllowList) {
    row.ipAllowList = input.ipAllowList;
    row.ipAllowlistCount = input.ipAllowList.length;
  }
  void backendStatus; // reserved for the future update endpoint
  return { client: row };
}

function mintLocalSecret(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
  let out = "lms_";
  for (let i = 0; i < 40; i += 1) {
    out += alphabet.charAt(Math.floor(Math.random() * alphabet.length));
  }
  return out;
}

export async function rotateApiClientSecret(
  id: string,
  _input: RotateApiClientSecretInput,
): Promise<RotateApiClientSecretResponse> {
  // Backend has no rotate endpoint yet — we mint a client-side secret so
  // the reveal flow still works in dev, but the backend's stored secret is
  // unchanged. Tracked in docs/INTEGRATION-STATUS.md.
  const all = await requestJson<BackendApiClientResponse[]>(BASE);
  const found = all.find((row) => row.id === id);
  if (!found) throw new Error(`API client ${id} not found`);
  const row = toRow(found);
  lastRotatedAtById.set(id, new Date().toISOString());
  return { client: row, clientSecret: mintLocalSecret() };
}

export function getApiClientLastRotatedAt(id: string): string | null {
  return lastRotatedAtById.get(id) ?? null;
}
