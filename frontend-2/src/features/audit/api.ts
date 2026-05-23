/**
 * Audit explorer API client.
 *
 * The backend has no unified audit endpoint — audit data is scattered
 * across per-domain streams. Per the issue #15 decision (recorded in
 * docs/INTEGRATION-STATUS.md), the unified feed is composed client-side
 * for SYSTEM_ADMIN sessions:
 *
 *   1. List the most recent N loan applications via
 *      `/api/v1/internal/ops/loan-applications`.
 *   2. For each app in parallel, fetch its `/audit-events` stream.
 *   3. Project every row to the common `AuditRow` shape and merge.
 *   4. Apply the filter snapshot client-side; slice for pagination.
 *
 * Constraints:
 *   - Only the APPLICATION stream is surfaced today. INTAKE,
 *     PII_REVEAL, DOCUMENT_ACCESS, and PRODUCT streams require
 *     additional per-domain fan-out and aren't materially used by the
 *     existing demo flows. The empty filter still lists APPLICATION
 *     events from the recent window.
 *   - The composition window is bounded by the application list page
 *     size (`AUDIT_COMPOSITION_APP_LIMIT`). Filters operate over that
 *     window only; older events are out of scope until a backend
 *     unified endpoint ships.
 *
 * For non-admin sessions, the call falls back to the mock router so the
 * dev experience remains usable.
 */
import { ApiError, buildQueryPath, requestJson } from "@/lib/api/http-client";
import { dispatch } from "@/mocks/router";
import { loadStoredSession } from "@/lib/api/session-storage";
import {
  AuditEventsResponseSchema,
  type AuditEventsFilters,
  type AuditEventsResponse,
  type AuditRow,
  type AuditStream,
} from "./types";

const APPLICATIONS_BASE = "/api/v1/internal/ops/loan-applications";
const AUDIT_COMPOSITION_APP_LIMIT = 50;
const AUDIT_COMPOSITION_PARALLELISM = 8;

function isSystemAdmin(): boolean {
  return loadStoredSession()?.user.role === "SYSTEM_ADMIN";
}

function buildAuditEventsQueryParams(
  filters: AuditEventsFilters,
): Record<string, string> {
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

export function buildAuditEventsQuery(filters: AuditEventsFilters): string {
  const params = new URLSearchParams(buildAuditEventsQueryParams(filters));
  return params.toString();
}

interface BackendApplicationRow {
  id: string;
  borrowerFullName: string | null;
}

interface BackendAuditEventRow {
  id: string;
  loanApplicationId: string;
  action: string;
  actorUsername: string | null;
  fromStatus: string | null;
  toStatus: string | null;
  note: string | null;
  reasonCode: string | null;
  correlationId: string | null;
  createdAt: string;
}

async function fetchRecentApplicationIds(): Promise<BackendApplicationRow[]> {
  const path = buildQueryPath(APPLICATIONS_BASE, {
    offset: 0,
    limit: AUDIT_COMPOSITION_APP_LIMIT,
    paginationDetails: "true",
  });
  const payload = await requestJson<
    | BackendApplicationRow[]
    | { items?: BackendApplicationRow[] }
  >(path);
  if (Array.isArray(payload)) return payload;
  return payload.items ?? [];
}

async function fetchAuditEventsForApplication(
  applicationId: string,
): Promise<BackendAuditEventRow[]> {
  try {
    return await requestJson<BackendAuditEventRow[]>(
      `${APPLICATIONS_BASE}/${encodeURIComponent(applicationId)}/audit-events`,
    );
  } catch {
    return [];
  }
}

function actionToHeadline(row: BackendAuditEventRow): string {
  if (row.action && row.action.trim() !== "") {
    const human = row.action.replace(/[_-]/g, " ").toLowerCase();
    return human.charAt(0).toUpperCase() + human.slice(1);
  }
  if (row.toStatus && row.fromStatus) {
    return `${row.fromStatus} → ${row.toStatus}`;
  }
  if (row.toStatus) return `Status: ${row.toStatus}`;
  return "Audit event";
}

function projectAuditRow(
  row: BackendAuditEventRow,
  borrowerByApplicationId: Map<string, string>,
): AuditRow {
  const actor = row.actorUsername ?? "system";
  return {
    id: row.id,
    stream: "APPLICATION",
    createdAt: row.createdAt,
    actorId: actor,
    actorName: actor,
    actorRole: null,
    correlationId: row.correlationId ?? row.id,
    subjectType: "LOAN_APPLICATION",
    subjectId: row.loanApplicationId,
    headline: actionToHeadline(row),
    raw: {
      ...row,
      borrowerFullName: borrowerByApplicationId.get(row.loanApplicationId) ?? null,
    },
  };
}

function passesFilters(row: AuditRow, filters: AuditEventsFilters): boolean {
  const streams = filters.streams ?? [];
  if (streams.length > 0 && !streams.includes(row.stream)) return false;
  if (filters.actorId && row.actorId !== filters.actorId) return false;
  if (filters.correlationId && row.correlationId !== filters.correlationId) return false;
  if (filters.dateFrom) {
    if (row.createdAt < filters.dateFrom) return false;
  }
  if (filters.dateTo) {
    // Inclusive of the dateTo day.
    if (row.createdAt > `${filters.dateTo}T23:59:59.999Z`) return false;
  }
  if (filters.q) {
    const q = filters.q.toLowerCase();
    if (!row.headline.toLowerCase().includes(q) && !row.actorName.toLowerCase().includes(q)) {
      return false;
    }
  }
  return true;
}

async function composeAuditEventsFromBackend(
  filters: AuditEventsFilters,
): Promise<AuditEventsResponse> {
  const applications = await fetchRecentApplicationIds();
  const borrowerByApplicationId = new Map(
    applications
      .filter((row) => !!row.borrowerFullName)
      .map((row) => [row.id, row.borrowerFullName ?? ""]),
  );

  // Limit concurrency so we don't fan out a hundred-plus requests at once.
  const events: BackendAuditEventRow[] = [];
  for (let i = 0; i < applications.length; i += AUDIT_COMPOSITION_PARALLELISM) {
    const batch = applications.slice(i, i + AUDIT_COMPOSITION_PARALLELISM);
    const results = await Promise.all(
      batch.map((row) => fetchAuditEventsForApplication(row.id)),
    );
    for (const result of results) events.push(...result);
  }

  const rows = events
    .map((row) => projectAuditRow(row, borrowerByApplicationId))
    .filter((row) => passesFilters(row, filters))
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));

  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const start = page * pageSize;
  const items = rows.slice(start, start + pageSize);

  return { items, total: rows.length, page, pageSize };
}

/**
 * Fetch the audit-events list for the active session.
 *
 * For SYSTEM_ADMIN sessions, the unified feed is composed client-side
 * from the per-domain backend endpoints (see file docstring). For other
 * roles or on 4xx backend errors, falls back to the mock router.
 */
export async function fetchAuditEvents(
  filters: AuditEventsFilters,
): Promise<AuditEventsResponse> {
  if (isSystemAdmin()) {
    try {
      return await composeAuditEventsFromBackend(filters);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status >= 500) throw error;
    }
  }

  return dispatch(
    {
      method: "GET",
      path: "/api/v1/audit/events",
      query: buildAuditEventsQueryParams(filters),
    },
    AuditEventsResponseSchema,
  );
}

export type { AuditStream };
