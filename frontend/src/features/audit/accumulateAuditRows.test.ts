import { describe, expect, it } from "vitest";
import {
  accumulateAuditRowsReducer,
  auditFilterKey,
  type AccumulatedAuditRowsState,
} from "./accumulateAuditRows";
import type { AuditRow } from "./types";

function row(id: string): AuditRow {
  return {
    id,
    stream: "APPLICATION",
    createdAt: "2026-06-07T10:00:00Z",
    actorId: "actor-1",
    actorName: "alice",
    actorRole: "OPS_USER",
    correlationId: "corr-1",
    subjectType: "LOAN_APPLICATION",
    subjectId: "app-1",
    headline: "Approved",
  };
}

const initial: AccumulatedAuditRowsState = { filterKey: "filters-a", rows: [] };

describe("accumulateAuditRowsReducer", () => {
  it("replaces rows on the first page load", () => {
    const next = accumulateAuditRowsReducer(initial, {
      type: "replace",
      filterKey: "filters-a",
      items: [row("1"), row("2")],
    });

    expect(next.rows).toHaveLength(2);
    expect(next.rows.map((item) => item.id)).toEqual(["1", "2"]);
  });

  it("appends rows when loading the next cursor page", () => {
    const afterFirst = accumulateAuditRowsReducer(initial, {
      type: "replace",
      filterKey: "filters-a",
      items: [row("1")],
    });

    const afterSecond = accumulateAuditRowsReducer(afterFirst, {
      type: "append",
      filterKey: "filters-a",
      items: [row("2")],
    });

    expect(afterSecond.rows.map((item) => item.id)).toEqual(["1", "2"]);
  });

  it("replaces instead of appending when filters change", () => {
    const afterFirst = accumulateAuditRowsReducer(initial, {
      type: "replace",
      filterKey: "filters-a",
      items: [row("1")],
    });

    const afterFilterChange = accumulateAuditRowsReducer(afterFirst, {
      type: "append",
      filterKey: "filters-b",
      items: [row("9")],
    });

    expect(afterFilterChange.filterKey).toBe("filters-b");
    expect(afterFilterChange.rows.map((item) => item.id)).toEqual(["9"]);
  });
});

describe("auditFilterKey", () => {
  it("ignores cursor and eventId so pagination shares one accumulation bucket", () => {
    const base = auditFilterKey({ streams: ["APPLICATION"], page: 0 });
    const withCursor = auditFilterKey({
      streams: ["APPLICATION"],
      page: 0,
      cursor: "next-page",
      eventId: "evt-1",
    });

    expect(base).toBe(withCursor);
  });
});
