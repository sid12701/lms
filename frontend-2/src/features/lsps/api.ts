/**
 * LSPs feature API wrapper (Phase 9 — Agent A).
 *
 * Re-exports the typed mock handlers so the hooks layer never reaches into
 * `@/mocks/api/*` directly. Importing this module also triggers the
 * side-effect route registration in `@/mocks/api/lsps` even before the
 * orchestrator wires the namespace into `@/mocks/api/index.ts`.
 */
import {
  createLsp as createLspImpl,
  getLspWebhookSubscription as getLspWebhookSubscriptionImpl,
  listLsps as listLspsImpl,
  updateLsp as updateLspImpl,
  upsertLspWebhookSubscription as upsertLspWebhookSubscriptionImpl,
} from "@/mocks/api/lsps";
import type {
  CreateLspInput,
  LspMutationResponse,
  LspsListFilters,
  LspsListResponse,
  UpdateLspInput,
  UpsertWebhookSubscriptionInput,
  WebhookSubscriptionResponse,
} from "./types";

export async function listLsps(
  filters: LspsListFilters = {},
): Promise<LspsListResponse> {
  return listLspsImpl(filters);
}

export async function createLsp(
  input: CreateLspInput,
): Promise<LspMutationResponse> {
  return createLspImpl(input);
}

export async function updateLsp(
  id: string,
  input: UpdateLspInput,
): Promise<LspMutationResponse> {
  return updateLspImpl(id, input);
}

export async function getLspWebhookSubscription(
  id: string,
): Promise<WebhookSubscriptionResponse> {
  return getLspWebhookSubscriptionImpl(id);
}

export async function upsertLspWebhookSubscription(
  id: string,
  input: UpsertWebhookSubscriptionInput,
): Promise<WebhookSubscriptionResponse> {
  return upsertLspWebhookSubscriptionImpl(id, input);
}
