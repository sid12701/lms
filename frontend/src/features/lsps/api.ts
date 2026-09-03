/**
 * LSP admin surface, wired to the live backend.
 *
 * Backend contract: `LspAdminController` under `/api/v1/internal/admin/lsps`
 * (SYSTEM_ADMIN only). The frontend shape is assembled from the backend
 * response; only fields the backend actually serves are displayed.
 */
import { requestJson } from "@/lib/api/http-client";
import type { Lsp, LspStatus, LspStatusChangeReason } from "@/schemas/lsp";
import type {
  CreateLspInput,
  LspAllowlistEnforcement,
  LspAuditEventRow,
  LspIpAllowlistEntry,
  LspIpAllowlistSurface,
  LspMutationResponse,
  LspRow,
  LspsListFilters,
  LspsListResponse,
  UpdateLspStatusInput,
} from "./types";

const BACKEND_BASE = "/api/v1/internal/admin/lsps";

const SUPPORTED_BACKEND_STATUSES = new Set(["ACTIVE", "SUSPENDED", "INACTIVE"]);

function normaliseStatus(value: string): LspStatus {
  const upper = value.trim().toUpperCase();
  if (upper === "DISABLED") return "INACTIVE";
  if (SUPPORTED_BACKEND_STATUSES.has(upper)) return upper as LspStatus;
  return "ACTIVE";
}

interface BackendLspResponse {
  id: string;
  code: string;
  name: string;
  status: string;
  createdAt?: string;
  userCount: number;
  portfolioSummary: unknown;
}

function projectLspRow(payload: BackendLspResponse): LspRow {
  const lsp: Lsp = {
    id: payload.id,
    code: payload.code,
    name: payload.name,
    status: normaliseStatus(payload.status),
    createdAt: payload.createdAt ?? "",
  };
  return {
    ...lsp,
    userCount: payload.userCount ?? 0,
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

interface BackendIpAllowlistEntry {
  id: string;
  lspId: string;
  cidr: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

function projectAllowlistEntry(payload: BackendIpAllowlistEntry): LspIpAllowlistEntry {
  return {
    id: payload.id,
    lspId: payload.lspId,
    cidr: payload.cidr,
    description: payload.description,
    createdAt: payload.createdAt,
    updatedAt: payload.updatedAt,
  };
}

function allowlistBase(lspId: string, surface: LspIpAllowlistSurface): string {
  const segment = surface === "ui" ? "ui-ip-allowlist" : "api-ip-allowlist";
  return `${BACKEND_BASE}/${encodeURIComponent(lspId)}/${segment}`;
}

export async function listLspIpAllowlist(
  lspId: string,
  surface: LspIpAllowlistSurface,
): Promise<LspIpAllowlistEntry[]> {
  const rows = await requestJson<BackendIpAllowlistEntry[]>(allowlistBase(lspId, surface), {
    cache: "no-store",
  });
  return rows.map(projectAllowlistEntry);
}

export async function addLspIpAllowlistEntry(
  lspId: string,
  surface: LspIpAllowlistSurface,
  input: { cidr: string; description?: string },
): Promise<LspIpAllowlistEntry> {
  const payload = await requestJson<BackendIpAllowlistEntry>(allowlistBase(lspId, surface), {
    method: "POST",
    body: JSON.stringify({
      cidr: input.cidr,
      description: input.description ?? null,
    }),
  });
  return projectAllowlistEntry(payload);
}

export async function removeLspIpAllowlistEntry(
  lspId: string,
  surface: LspIpAllowlistSurface,
  entryId: string,
): Promise<void> {
  await requestJson<void>(`${allowlistBase(lspId, surface)}/${encodeURIComponent(entryId)}`, {
    method: "DELETE",
  });
}

export async function getLspAllowlistEnforcement(lspId: string): Promise<LspAllowlistEnforcement> {
  return requestJson<LspAllowlistEnforcement>(
    `${BACKEND_BASE}/${encodeURIComponent(lspId)}/allowlist-enforcement`,
    { cache: "no-store" },
  );
}

export async function updateLspAllowlistEnforcement(
  lspId: string,
  input: Partial<LspAllowlistEnforcement>,
): Promise<LspAllowlistEnforcement> {
  return requestJson<LspAllowlistEnforcement>(
    `${BACKEND_BASE}/${encodeURIComponent(lspId)}/allowlist-enforcement`,
    {
      method: "PUT",
      body: JSON.stringify(input),
    },
  );
}
