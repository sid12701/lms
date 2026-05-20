/**
 * API-clients feature API wrapper.
 *
 * Re-exports the typed mock handlers so the hooks layer never reaches into
 * `@/mocks/api/*` directly. Importing this module also triggers the
 * side-effect route registration in `@/mocks/api/api-clients`.
 */
import {
  createApiClient as createApiClientImpl,
  getApiClientLastRotatedAt as getApiClientLastRotatedAtImpl,
  listApiClients as listApiClientsImpl,
  rotateApiClientSecret as rotateApiClientSecretImpl,
  updateApiClient as updateApiClientImpl,
} from "@/mocks/api/api-clients";
import type {
  ApiClientMutationResponse,
  ApiClientsListFilters,
  ApiClientsListResponse,
  CreateApiClientInput,
  CreateApiClientResponse,
  RotateApiClientSecretInput,
  RotateApiClientSecretResponse,
  UpdateApiClientInput,
} from "./types";

export async function listApiClients(
  filters: ApiClientsListFilters = {},
): Promise<ApiClientsListResponse> {
  return listApiClientsImpl(filters);
}

export async function createApiClient(
  input: CreateApiClientInput,
): Promise<CreateApiClientResponse> {
  return createApiClientImpl(input);
}

export async function updateApiClient(
  id: string,
  input: UpdateApiClientInput,
): Promise<ApiClientMutationResponse> {
  return updateApiClientImpl(id, input);
}

export async function rotateApiClientSecret(
  id: string,
  input: RotateApiClientSecretInput,
): Promise<RotateApiClientSecretResponse> {
  return rotateApiClientSecretImpl(id, input);
}

export function getApiClientLastRotatedAt(id: string): string | null {
  return getApiClientLastRotatedAtImpl(id);
}
