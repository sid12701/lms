/**
 * Audit explorer API client.
 *
 * Gap #3 — the backend now exposes a single unified endpoint at
 *   GET /api/v1/internal/admin/audit-events
 * that UNION-ALLs across the seven supported audit streams on the server
 * side. This module routes
 * the page's URL-bound filters into that request, projects each row into
 * the long-standing {@link AuditRow} shape so the table/sheet keep
 * rendering unchanged, and applies the few remaining client-side post-
 * filters (correlationId, free-text `q`) that the backend doesn't expose.
 *
 * For non-SYSTEM_ADMIN sessions the call falls back to the mock router so
 * local dev / Storybook flows still work.
 */
import { buildQueryPath, requestJson } from "@/lib/api/http-client";
import { dispatch } from "@/mocks/router";
import { loadStoredSession } from "@/lib/api/session-storage";
import {
  AuditEventsResponseSchema,
  type AuditEventsFilters,
  type AuditEventsResponse,
  type AuditRow,
  type AuditStream,
  type AuditSubjectType,
} from "./types";

const ENDPOINT = "/api/v1/internal/admin/audit-events";
const DEFAULT_PAGE_SIZE = 25;

/** Streams backed by the live unified audit endpoint (#159 / #152). */
const BACKEND_STREAMS: ReadonlySet<AuditStream> = new Set<AuditStream>([
  "APPLICATION",
  "INTAKE",
  "DOCUMENT_ACCESS",
  "PRODUCT",
  "APP_USER",
  "API_CLIENT",
  "DISBURSEMENT",
]);

interface BackendUnifiedAuditEvent {
  id: string;
  stream: AuditStream;
  occurredAt: string;
  actorUsername: string;
  loanApplicationId: string | null;
  borrowerId: string | null;
  lspId: string | null;
  productId: string | null;
  action: string;
  summary: string;
  detail: Record<string, unknown>;
  correlationId: string | null;
}

interface BackendPagedAuditEvents {
  items: BackendUnifiedAuditEvent[];
  totalCount: number;
  offset: number;
  limit: number;
}

function isSystemAdmin(): boolean {
  return loadStoredSession()?.user.role === "SYSTEM_ADMIN";
}

function backendSubsetOf(streams: readonly AuditStream[] | undefined): AuditStream[] | undefined {
  if (!streams || streams.length === 0) return undefined;
  const filtered = streams.filter((s) => BACKEND_STREAMS.has(s));
  return filtered.length > 0 ? filtered : undefined;
}

function toIsoStart(date: string | undefined): string | undefined {
  if (!date) return undefined;
  return `${date}T00:00:00Z`;
}

function toIsoEnd(date: string | undefined): string | undefined {
  if (!date) return undefined;
  return `${date}T23:59:59.999Z`;
}

function buildBackendQueryParams(
  filters: AuditEventsFilters,
): Record<string, string | number | undefined> {
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const page = filters.page ?? 0;
  const streams = backendSubsetOf(filters.streams);

  const params: Record<string, string | number | undefined> = {
    paginationDetails: "true",
    limit: pageSize,
    offset: page * pageSize,
  };
  if (streams) params["streams"] = streams.join(",");
  if (filters.actorId) params["actorUsername"] = filters.actorId;
  const since = toIsoStart(filters.dateFrom);
  if (since) params["since"] = since;
  const until = toIsoEnd(filters.dateTo);
  if (until) params["until"] = until;
  return params;
}

function readDetailId(detail: Record<string, unknown>, key: string): string | null {
  const value = detail[key];
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function subjectFor(event: BackendUnifiedAuditEvent): {
  subjectType: AuditSubjectType | null;
  subjectId: string | null;
} {
  if (event.stream === "PRODUCT") {
    return { subjectType: "LOAN_PRODUCT", subjectId: event.productId };
  }
  if (event.stream === "APP_USER") {
    return {
      subjectType: "APP_USER",
      subjectId: readDetailId(event.detail, "userId"),
    };
  }
  if (event.stream === "API_CLIENT") {
    return {
      subjectType: "API_CLIENT",
      subjectId: readDetailId(event.detail, "apiClientId"),
    };
  }
  if (event.loanApplicationId) {
    return { subjectType: "LOAN_APPLICATION", subjectId: event.loanApplicationId };
  }
  return { subjectType: null, subjectId: null };
}

function projectAuditRow(event: BackendUnifiedAuditEvent): AuditRow {
  const subject = subjectFor(event);
  return {
    id: event.id,
    stream: event.stream,
    createdAt: event.occurredAt,
    actorId: event.actorUsername,
    actorName: event.actorUsername,
    actorRole: null,
    correlationId: event.correlationId ?? event.id,
    subjectType: subject.subjectType,
    subjectId: subject.subjectId,
    headline: event.summary && event.summary.trim() !== "" ? event.summary : humanize(event.action),
    raw: event,
  };
}

function humanize(action: string | null | undefined): string {
  if (!action) return "Audit event";
  const lowered = action.replace(/[_-]/g, " ").toLowerCase();
  return lowered.charAt(0).toUpperCase() + lowered.slice(1);
}

function passesClientFilters(row: AuditRow, filters: AuditEventsFilters): boolean {
  if (filters.correlationId && row.correlationId !== filters.correlationId) return false;
  if (filters.q && filters.q.trim() !== "") {
    const q = filters.q.toLowerCase();
    const headline = row.headline.toLowerCase();
    const actor = row.actorName.toLowerCase();
    if (!headline.includes(q) && !actor.includes(q)) return false;
  }
  return true;
}

async function fetchFromBackend(filters: AuditEventsFilters): Promise<AuditEventsResponse> {
  const path = buildQueryPath(ENDPOINT, buildBackendQueryParams(filters));
  const payload = await requestJson<BackendPagedAuditEvents>(path);
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const page = filters.page ?? 0;

  const projected = payload.items.map(projectAuditRow);
  const visible = projected.filter((row) => passesClientFilters(row, filters));

  const total =
    payload.totalCount >= 0
      ? payload.totalCount
      : // BE returned the -1 sentinel — fall back to the visible-window size.
        visible.length + page * pageSize;

  return {
    items: visible,
    total,
    page,
    pageSize,
  };
}

/**
 * Fetch the audit-events page for the active session.
 *
 * SYSTEM_ADMIN sessions hit the live unified backend endpoint; non-admin
 * contexts use the mock router (Storybook, tests, LSP demo) per #78.
 */
export async function fetchAuditEvents(filters: AuditEventsFilters): Promise<AuditEventsResponse> {
  if (isSystemAdmin()) {
    return fetchFromBackend(filters);
  }

  // Non-admin / Storybook: mock-router demo path (#78).
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/audit/events",
      query: buildMockQueryParams(filters),
    },
    AuditEventsResponseSchema,
  );
}

function buildMockQueryParams(filters: AuditEventsFilters): Record<string, string> {
  const out: Record<string, string> = {};
  if (filters.streams && filters.streams.length > 0) {
    out["streams"] = filters.streams.join(",");
  }
  if (filters.actorId) out["actorId"] = filters.actorId;
  if (filters.correlationId) out["correlationId"] = filters.correlationId;
  if (filters.dateFrom) out["dateFrom"] = filters.dateFrom;
  if (filters.dateTo) out["dateTo"] = filters.dateTo;
  if (filters.q && filters.q.trim() !== "") out["q"] = filters.q;
  if (typeof filters.page === "number") out["page"] = String(filters.page);
  if (typeof filters.pageSize === "number") out["pageSize"] = String(filters.pageSize);
  return out;
}

/** Retained for URL-bound filter callers that still want to serialise a snapshot. */
export function buildAuditEventsQuery(filters: AuditEventsFilters): string {
  return new URLSearchParams(buildMockQueryParams(filters)).toString();
}

export type { AuditStream };
