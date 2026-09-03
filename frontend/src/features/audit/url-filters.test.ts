import { describe, expect, it } from "vitest";
import {
  auditFilterLabelFor,
  parseAuditFiltersFromUrl,
  serializeAuditFiltersToUrl,
} from "./url-filters";

const parse = (search: string) => parseAuditFiltersFromUrl(new URLSearchParams(search));

describe("parseAuditFiltersFromUrl", () => {
  it("keeps an existing single-stream deep link working", () => {
    const { filters, ignoredKeys } = parse("streams=APPLICATION&page=0");
    expect(filters.streams).toEqual(["APPLICATION"]);
    expect(ignoredKeys).toEqual([]);
  });

  it("keeps the alerts correlation deep link working", () => {
    const { filters, ignoredKeys } = parse("correlationId=corr-deep");
    expect(filters.correlationId).toBe("corr-deep");
    expect(ignoredKeys).toEqual([]);
  });

  it("reports a multi-stream link as ignored instead of applying part of it", () => {
    // The selector holds one stream. Applying only the first would leave the
    // control reading "Application" over a wider result set — the same silent
    // mismatch F-01 is about.
    const { filters, ignoredKeys } = parse("streams=APPLICATION&streams=INTAKE");
    expect(filters.streams).toBeUndefined();
    expect(ignoredKeys).toEqual(["streams"]);
  });

  it("reports a comma-joined multi-stream link as ignored", () => {
    const { filters, ignoredKeys } = parse("streams=APPLICATION,INTAKE");
    expect(filters.streams).toBeUndefined();
    expect(ignoredKeys).toEqual(["streams"]);
  });

  it("reports a stream the backend cannot filter on as ignored", () => {
    // PII_REVEAL was offered by the old tab strip and silently widened the
    // result set to every stream. It is neither offered nor silent now.
    const { filters, ignoredKeys } = parse("streams=PII_REVEAL");
    expect(filters.streams).toBeUndefined();
    expect(ignoredKeys).toEqual(["streams"]);
  });

  it("leaves the remaining filters applied when the stream is ignored", () => {
    const { filters, ignoredKeys } = parse("streams=PII_REVEAL&correlationId=corr-deep");
    expect(filters.correlationId).toBe("corr-deep");
    expect(ignoredKeys).toEqual(["streams"]);
  });

  it("names an ignored key the way the control is labelled", () => {
    expect(auditFilterLabelFor("streams")).toBe("Stream");
  });
});

describe("serializeAuditFiltersToUrl", () => {
  it("round-trips a filter set through the query string", () => {
    const filters = {
      streams: ["DISBURSEMENT" as const],
      actorId: "alice.ops",
      correlationId: "corr-deep",
      dateFrom: "2026-04-01",
      dateTo: "2026-04-30",
      page: 0,
    };
    const round = parseAuditFiltersFromUrl(serializeAuditFiltersToUrl(filters));
    expect(round.filters).toEqual(filters);
    expect(round.ignoredKeys).toEqual([]);
  });
});
