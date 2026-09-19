/**
 * H29 — the list request must carry every selected status and the advertised
 * sort choice to the server, so the page the table shows is the page the
 * server filtered and sorted. Fixing this by client-sorting the loaded page
 * is explicitly not a fix; these tests pin the request contract instead.
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
  requestJsonWithHeaders: requestJsonWithHeadersMock,
}));

vi.mock("@/lib/api/pagination-headers", () => ({
  readPaginationHeaders: (headers: Headers) => ({
    totalCount: headers.get("X-Total-Count") == null ? null : Number(headers.get("X-Total-Count")),
    limit: null,
    offset: null,
  }),
}));

import { fetchLoanApplications } from "./api";

function row(id: string) {
  return {
    id,
    borrowerId: "22222222-2222-4222-8222-222222222222",
    borrowerFullName: "A•••a Devi",
    lspId: "33333333-3333-4333-8333-333333333333",
    lspCode: "APEX",
    lspName: "Apex NBFC",
    productId: "44444444-4444-4444-8444-444444444444",
    productCode: "PL-A",
    productName: "Personal Loan A",
    externalLoanId: null,
    accountNumber: null,
    sourceChannel: null,
    requestedAmount: 100_000,
    tenureMonths: 12,
    status: "INITIALIZED",
    createdAt: "2026-05-10T08:00:00.000Z",
  };
}

function mockPage(items: ReturnType<typeof row>[], total: string) {
  requestJsonWithHeadersMock.mockResolvedValue({
    data: items,
    headers: new Headers({ "X-Total-Count": total }),
  });
}

function requestedPath(): string {
  return String(requestJsonWithHeadersMock.mock.calls[0]?.[0] ?? "");
}

function requestedParams(): URLSearchParams {
  const path = requestedPath();
  return new URLSearchParams(path.slice(path.indexOf("?") + 1));
}

beforeEach(() => {
  requestJsonWithHeadersMock.mockReset();
});

describe("fetchLoanApplications (H29 truthful contract)", () => {
  it("sends every selected status as repeated params, not just the first", async () => {
    mockPage([row("a-1")], "1");
    await fetchLoanApplications({ status: ["INITIALIZED", "AWAITING_APPROVAL"] });
    expect(requestedParams().getAll("status")).toEqual(["INITIALIZED", "AWAITING_APPROVAL"]);
  });

  it("sends the advertised sort choice with the request", async () => {
    mockPage([row("a-1")], "1");
    await fetchLoanApplications({ sortBy: "requestedAmount", sortDir: "desc" });
    expect(requestedParams().get("sortBy")).toBe("requestedAmount");
    expect(requestedParams().get("sortDir")).toBe("desc");
  });

  it("omits sort params when no sort is chosen, leaving the server default", async () => {
    mockPage([row("a-1")], "1");
    await fetchLoanApplications({});
    expect(requestedParams().has("sortBy")).toBe(false);
    expect(requestedParams().has("sortDir")).toBe(false);
  });

  it("keeps translating page/pageSize to offset/limit with pagination details", async () => {
    mockPage([row("a-1")], "40");
    const result = await fetchLoanApplications({ page: 2, pageSize: 10 });
    expect(requestedParams().get("offset")).toBe("20");
    expect(requestedParams().get("limit")).toBe("10");
    expect(requestedParams().get("paginationDetails")).toBe("ON");
    expect(result.total).toBe(40);
    expect(result.page).toBe(2);
    expect(result.pageSize).toBe(10);
  });

  it("preserves unknown backend statuses instead of folding them", async () => {
    mockPage([{ ...row("a-9"), status: "SOME_FUTURE_STATUS" }], "1");
    const result = await fetchLoanApplications({});
    expect(result.items[0]?.status).toBe("UNKNOWN:SOME_FUTURE_STATUS");
  });
});
