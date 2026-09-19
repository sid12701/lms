/**
 * H30 — the inbox is server-paginated and server-filtered. The client must
 * send offset/limit/paginationDetails plus every filter, and take the total
 * from the pagination headers. Fetching the first page and filtering locally
 * hid older matching alerts; these tests pin the server contract instead.
 *
 * HTTP-mock boundary: mocks `@/lib/api/http-client` requestJsonWithHeaders.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

const requestJsonWithHeadersMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", () => ({
  ApiError: class ApiError extends Error {},
  buildQueryPath: (path: string, params: Record<string, unknown>) => {
    const search = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v == null || v === "") continue;
      if (Array.isArray(v)) {
        for (const item of v) search.append(k, String(item));
        continue;
      }
      search.set(k, String(v));
    }
    const qs = search.toString();
    return qs ? `${path}?${qs}` : path;
  },
  requestJson: vi.fn(),
  requestJsonWithHeaders: requestJsonWithHeadersMock,
}));

vi.mock("@/lib/api/pagination-headers", () => ({
  readPaginationHeaders: (headers: Headers) => ({
    totalCount: headers.get("X-Total-Count") == null ? null : Number(headers.get("X-Total-Count")),
    limit: null,
    offset: null,
  }),
}));

import { listAlerts } from "./api";

function backendRow(index: number, overrides: Record<string, unknown> = {}) {
  return {
    id: `aaaaaaaa-aaaa-4aaa-8aaa-${String(index).padStart(12, "0")}`,
    type: "DPD_BUCKET_TRANSITION",
    severity: "HIGH",
    status: "NEW",
    title: `Alert ${index}`,
    message: `Message ${index}`,
    subjectType: "LOAN_APPLICATION",
    subjectId: `bbbbbbbb-bbbb-4bbb-8bbb-${String(index).padStart(12, "0")}`,
    correlationId: `cccccccc-cccc-4ccc-8ccc-${String(index).padStart(12, "0")}`,
    contextJson: null,
    createdAt: `2026-05-26T10:${String(index % 60).padStart(2, "0")}:00.000Z`,
    acknowledgedAt: null,
    acknowledgedByUsername: null,
    acknowledgementNote: null,
    ...overrides,
  };
}

function mockBackend(rows: ReturnType<typeof backendRow>[], total: number) {
  requestJsonWithHeadersMock.mockResolvedValue({
    data: rows,
    headers: new Headers({ "X-Total-Count": String(total) }),
  });
}

function requestedParams(): URLSearchParams {
  const path = String(requestJsonWithHeadersMock.mock.calls[0]?.[0] ?? "");
  return new URLSearchParams(path.slice(path.indexOf("?") + 1));
}

beforeEach(() => {
  requestJsonWithHeadersMock.mockReset();
});

describe("listAlerts (H30 server pagination)", () => {
  it("sends offset/limit with pagination details and reads the total from headers", async () => {
    mockBackend([backendRow(1), backendRow(2)], 55);
    const result = await listAlerts({ page: 1, pageSize: 25 });
    const params = requestedParams();
    expect(params.get("offset")).toBe("25");
    expect(params.get("limit")).toBe("25");
    expect(params.get("paginationDetails")).toBe("ON");
    expect(result.items).toHaveLength(2);
    expect(result.total).toBe(55);
    expect(result.page).toBe(1);
  });

  it("sends severity, subject, text and status filters to the backend", async () => {
    mockBackend([], 0);
    await listAlerts({
      status: "OPEN",
      severity: ["CRITICAL", "HIGH"],
      subjectType: "BORROWER",
      q: "delinquent",
    });
    const params = requestedParams();
    expect(params.get("status")).toBe("NEW");
    expect(params.getAll("severity")).toEqual(["CRITICAL", "HIGH"]);
    expect(params.get("subjectType")).toBe("BORROWER");
    expect(params.get("q")).toBe("delinquent");
  });

  it("finds a severe match that only exists on a later page, with agreeing totals", async () => {
    // Page 0: routine alerts. Page 1: the severe match. The client must not
    // filter these locally — each page comes from the server with its total.
    const page0 = [backendRow(1), backendRow(2)];
    const severe = backendRow(51, {
      severity: "CRITICAL",
      title: "Severe late-page delinquency",
    });
    requestJsonWithHeadersMock
      .mockResolvedValueOnce({ data: page0, headers: new Headers({ "X-Total-Count": "55" }) })
      .mockResolvedValueOnce({ data: [severe], headers: new Headers({ "X-Total-Count": "55" }) });

    const first = await listAlerts({ page: 0, pageSize: 50 });
    expect(first.items.map((a) => a.title)).not.toContain("Severe late-page delinquency");
    expect(first.total).toBe(55);

    const second = await listAlerts({ page: 1, pageSize: 50 });
    expect(second.items.map((a) => a.title)).toContain("Severe late-page delinquency");
    expect(second.total).toBe(55);
    expect(second.items[0]?.severity).toBe("CRITICAL");
  });

  it("models unknown severities and missing subjects explicitly (H28)", async () => {
    mockBackend(
      [
        backendRow(1, { severity: "URGENT", subjectType: "SPACE_LASER", subjectId: null }),
        backendRow(2, { correlationId: "" }),
      ],
      2,
    );
    const result = await listAlerts({});
    expect(result.items[0]?.severity).toBe("UNKNOWN:URGENT");
    expect(result.items[0]?.subjectType).toBe("UNKNOWN:SPACE_LASER");
    expect(result.items[0]?.subjectId).toBeNull();
    expect(result.items[1]?.correlationId).toBeNull();
  });
});
