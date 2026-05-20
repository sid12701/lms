/**
 * Audit explorer API client — thin wrapper around the mock router.
 *
 * The contract is owned by `./types.ts`; this module registers a runtime
 * Zod parser (`AuditEventsResponseSchema`) so the router's drift detection
 * fires when the handler returns an unexpected shape. The mock handler
 * lives at `@/mocks/api/audit.ts`.
 */
import { dispatch } from "@/mocks/router";
import {
  AuditEventsResponseSchema,
  type AuditEventsFilters,
  type AuditEventsResponse,
  type AuditStream,
} from "./types";

/**
 * Serialise URL filters into the wire format the mock handler expects.
 *
 * - `streams` is comma-joined (the handler accepts both repeated params
 *   and a comma-joined single param).
 * - `undefined` / empty values are omitted.
 */
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

/**
 * Fetch the audit-events list for the active session. The handler enforces
 * the SYSTEM_ADMIN-only role gate; this wrapper does not duplicate it.
 */
export async function fetchAuditEvents(
  filters: AuditEventsFilters,
): Promise<AuditEventsResponse> {
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
