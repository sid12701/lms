import type { AuditRow } from "./types";

export interface AccumulatedAuditRowsState {
  filterKey: string;
  rows: readonly AuditRow[];
}

export type AccumulateAuditRowsAction =
  | {
      type: "replace";
      filterKey: string;
      items: readonly AuditRow[];
    }
  | {
      type: "append";
      filterKey: string;
      items: readonly AuditRow[];
    };

export function accumulateAuditRowsReducer(
  state: AccumulatedAuditRowsState,
  action: AccumulateAuditRowsAction,
): AccumulatedAuditRowsState {
  if (action.type === "replace") {
    return { filterKey: action.filterKey, rows: action.items };
  }

  if (state.filterKey !== action.filterKey) {
    return { filterKey: action.filterKey, rows: action.items };
  }

  return {
    filterKey: action.filterKey,
    rows: [...state.rows, ...action.items],
  };
}

/** Stable key for every filter dimension except cursor (pagination token). */
export function auditFilterKey(filters: Record<string, unknown>): string {
  const { cursor: _cursor, eventId: _eventId, ...rest } = filters;
  return JSON.stringify(rest);
}
