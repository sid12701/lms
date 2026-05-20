/**
 * Alerts feature API wrapper.
 *
 * Re-exports the typed mock handlers so the hooks layer never reaches into
 * `@/mocks/api/*` directly. Importing this module also triggers the
 * side-effect route registration in `@/mocks/api/alerts` (which is not
 * imported by `@/mocks/api/index.ts` — Phase-8 self-registration pattern).
 */
import {
  acknowledgeAlert as acknowledgeAlertImpl,
  listAlerts as listAlertsImpl,
} from "@/mocks/api/alerts";
import type {
  AcknowledgeAlertInput,
  AcknowledgeAlertResponse,
  AlertsListFilters,
  AlertsListResponse,
} from "./types";

export async function listAlerts(
  filters: AlertsListFilters = {},
): Promise<AlertsListResponse> {
  return listAlertsImpl(filters);
}

export async function acknowledgeAlert(
  id: string,
  input: AcknowledgeAlertInput,
): Promise<AcknowledgeAlertResponse> {
  return acknowledgeAlertImpl(id, input);
}
