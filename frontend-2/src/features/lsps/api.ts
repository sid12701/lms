/**
 * LSP admin surface, wired to the live backend.
 *
 * Backend contract: `LspAdminController` under `/api/v1/internal/admin/lsps`
 * (SYSTEM_ADMIN only). The frontend shape is assembled from the backend
 * response — fields the backend does not surface (createdAt timestamps,
 * apiClientCount) are filled in with safe defaults so the existing UI
 * continues to render.
 */
import { requestJson } from "@/lib/api/http-client";
import type { Lsp, LspStatus, LspStatusChangeReason, LspWebhookSubscription } from "@/schemas/lsp";
import type {
  CreateLspInput,
  LspAuditEventRow,
  LspMutationResponse,
  LspRow,
  LspsListFilters,
  LspsListResponse,
  UpdateLspStatusInput,
  UpsertWebhookSubscriptionInput,
  WebhookSubscriptionResponse,
} from "./types";

const BACKEND_BASE = "/api/v1/internal/admin/lsps";

const FRONTEND_TO_BACKEND_EVENT: Record<string, string> = {
  "loan.created": "LOAN_CREATED",
  "loan.status.changed": "LOAN_STATUS_CHANGED",
  "loan.disbursement.completed": "LOAN_DISBURSEMENT_UPDATED",
  "loan.repayment.posted": "LOAN_REPAYMENT_RECORDED",
  "loan.foreclosed": "LOAN_FORECLOSURE_COMPLETED",
};

const BACKEND_TO_FRONTEND_EVENT: Record<string, LspWebhookSubscription["eventTypes"][number]> = {
  LOAN_CREATED: "loan.created",
  LOAN_STATUS_CHANGED: "loan.status.changed",
  LOAN_DISBURSEMENT_UPDATED: "loan.disbursement.completed",
  LOAN_REPAYMENT_RECORDED: "loan.repayment.posted",
  LOAN_FORECLOSURE_COMPLETED: "loan.foreclosed",
};

const SUPPORTED_BACKEND_STATUSES = new Set(["ACTIVE", "SUSPENDED", "INACTIVE"]);

function normaliseStatus(value: string): LspStatus {
  const upper = value.trim().toUpperCase();
  if (upper === "DISABLED") return "INACTIVE";
  if (SUPPORTED_BACKEND_STATUSES.has(upper)) return upper as LspStatus;
  return "ACTIVE";
}

interface BackendWebhookSubscriptionResponse {
  enabled: boolean;
  endpointUrl: string | null;
  signingSecret: string | null;
  eventTypes: string[];
}

interface BackendLspResponse {
  id: string;
  code: string;
  name: string;
  status: string;
  webhookSubscription: BackendWebhookSubscriptionResponse;
  userCount: number;
  portfolioSummary: unknown;
}

function projectLspRow(payload: BackendLspResponse): LspRow {
  const lsp: Lsp = {
    id: payload.id,
    code: payload.code,
    name: payload.name,
    status: normaliseStatus(payload.status),
    createdAt: new Date(0).toISOString(),
  };
  return {
    ...lsp,
    apiClientCount: 0,
    webhookEnabled: payload.webhookSubscription?.enabled ?? false,
  };
}

function projectWebhookSubscription(
  lspId: string,
  payload: BackendWebhookSubscriptionResponse | null | undefined,
): LspWebhookSubscription | null {
  if (!payload || !payload.endpointUrl || !payload.signingSecret) return null;
  const eventTypes = (payload.eventTypes ?? [])
    .map((event) => BACKEND_TO_FRONTEND_EVENT[event])
    .filter((event): event is LspWebhookSubscription["eventTypes"][number] => Boolean(event));
  if (eventTypes.length === 0) return null;
  return {
    lspId,
    enabled: payload.enabled,
    endpointUrl: payload.endpointUrl,
    signingSecret: payload.signingSecret,
    eventTypes,
  };
}

export async function listLsps(filters: LspsListFilters = {}): Promise<LspsListResponse> {
  const all = await requestJson<BackendLspResponse[]>(
    BACKEND_BASE,
    { cache: "no-store" },
    { dedupe: false },
  );
  const filtered = all.filter((row) => {
    if (filters.status && normaliseStatus(row.status) !== filters.status) return false;
    if (filters.q) {
      const needle = filters.q.toLowerCase();
      if (!row.code.toLowerCase().includes(needle) && !row.name.toLowerCase().includes(needle)) {
        return false;
      }
    }
    return true;
  });
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 20;
  const start = page * pageSize;
  const items = filtered.slice(start, start + pageSize).map(projectLspRow);
  return { items, total: filtered.length, page, pageSize };
}

export async function createLsp(input: CreateLspInput): Promise<LspMutationResponse> {
  const payload = await requestJson<BackendLspResponse>(
    BACKEND_BASE,
    {
      method: "POST",
      body: JSON.stringify({ code: input.code, name: input.name, status: "ACTIVE" }),
    },
    { idempotencyKey: input.idempotencyKey },
  );
  return { lsp: projectLspRow(payload) };
}

export async function updateLspStatus(
  id: string,
  input: UpdateLspStatusInput,
): Promise<LspMutationResponse> {
  const statusPayload = await requestJson<BackendLspResponse>(
    `${BACKEND_BASE}/${encodeURIComponent(id)}/status`,
    {
      method: "PUT",
      body: JSON.stringify({
        status: input.status,
        reason: input.reason,
        note: input.note.trim(),
      }),
      cache: "no-store",
    },
    { idempotencyKey: input.idempotencyKey, dedupe: false },
  );
  return { lsp: projectLspRow(statusPayload) };
}

interface BackendLspAuditEventResponse {
  id: string;
  lspId: string;
  action: string;
  actorUsername: string;
  reason: string | null;
  note: string | null;
  cascadedClientCount: number;
  correlationId: string | null;
  createdAt: string;
}

const AUDIT_REASONS = new Set<LspStatusChangeReason>([
  "SECURITY_INCIDENT",
  "COMPLIANCE",
  "OFFBOARDING",
  "OPERATIONAL",
]);

function projectAuditEvent(payload: BackendLspAuditEventResponse): LspAuditEventRow {
  const reason =
    payload.reason && AUDIT_REASONS.has(payload.reason as LspStatusChangeReason)
      ? (payload.reason as LspStatusChangeReason)
      : null;
  return {
    id: payload.id,
    lspId: payload.lspId,
    action: payload.action,
    actorUsername: payload.actorUsername,
    reason,
    note: payload.note,
    cascadedClientCount: payload.cascadedClientCount,
    correlationId: payload.correlationId,
    createdAt: payload.createdAt,
  };
}

export async function listLspAuditEvents(lspId: string): Promise<LspAuditEventRow[]> {
  const rows = await requestJson<BackendLspAuditEventResponse[]>(
    `${BACKEND_BASE}/${encodeURIComponent(lspId)}/audit-events`,
    { cache: "no-store" },
    { dedupe: false },
  );
  if (!Array.isArray(rows)) {
    throw new Error("Unexpected audit-events response shape (expected JSON array).");
  }
  return rows.map(projectAuditEvent);
}

export async function getLspWebhookSubscription(id: string): Promise<WebhookSubscriptionResponse> {
  const payload = await requestJson<BackendLspResponse>(`${BACKEND_BASE}/${id}`);
  return { subscription: projectWebhookSubscription(id, payload.webhookSubscription) };
}

export async function upsertLspWebhookSubscription(
  id: string,
  input: UpsertWebhookSubscriptionInput,
): Promise<WebhookSubscriptionResponse> {
  const backendEventTypes = Array.from(
    new Set(
      input.eventTypes
        .map((event) => FRONTEND_TO_BACKEND_EVENT[event])
        .filter((event): event is string => Boolean(event)),
    ),
  );
  const payload = await requestJson<BackendLspResponse>(
    `${BACKEND_BASE}/${id}/webhook-subscription`,
    {
      method: "PUT",
      body: JSON.stringify({
        enabled: input.enabled,
        endpointUrl: input.endpointUrl,
        signingSecret: input.signingSecret,
        eventTypes: backendEventTypes,
      }),
    },
    { idempotencyKey: input.idempotencyKey },
  );
  return { subscription: projectWebhookSubscription(id, payload.webhookSubscription) };
}
