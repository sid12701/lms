import { afterEach, describe, expect, it, vi } from "vitest";

const requestJsonWithHeadersMock = vi.hoisted(() => vi.fn());
const requestJsonMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/http-client")>();
  return {
    ...actual,
    requestJson: requestJsonMock,
    requestJsonWithHeaders: requestJsonWithHeadersMock,
  };
});

vi.mock("@/lib/api/session-storage", () => ({
  loadStoredSession: () => ({
    user: { role: "LSP_UI_READ", roles: ["LSP_UI_READ"] },
  }),
}));

import { fetchMyLoanPayments, fetchMyLoanPaymentsPage } from "./api";

function backendPayment(id: string) {
  return {
    id,
    amount: 1000,
    paymentDate: "2026-08-05",
    channel: "UPI",
    reference: `REF-${id}`,
    status: "RECEIVED",
    createdAt: "2026-08-05T10:00:00.000Z",
  };
}

function pageOf(
  count: number,
  startIndex: number,
  totalCount: number,
  offset: number,
  limit: number,
) {
  return {
    data: Array.from({ length: count }, (_, index) => backendPayment(`pay-${startIndex + index}`)),
    headers: new Headers({
      "X-Total-Count": String(totalCount),
      "X-Limit": String(limit),
      "X-Offset": String(offset),
    }),
  };
}

afterEach(() => {
  requestJsonMock.mockReset();
  requestJsonWithHeadersMock.mockReset();
});

describe("fetchMyLoanPaymentsPage", () => {
  it("requests a bounded page with paginationDetails=ON and maps the headers", async () => {
    requestJsonWithHeadersMock.mockResolvedValue(pageOf(2, 0, 53, 50, 2));

    const result = await fetchMyLoanPaymentsPage("loan-1", {
      offset: 50,
      limit: 2,
    });

    expect(requestJsonWithHeadersMock).toHaveBeenCalledWith(
      "/api/v1/lsp/loans/loan-1/payments?offset=50&limit=2&paginationDetails=ON",
      { signal: undefined },
    );
    expect(result.items).toHaveLength(2);
    expect(result.totalCount).toBe(53);
    expect(result.offset).toBe(50);
    expect(result.limit).toBe(2);
  });
});

describe("fetchMyLoanPayments", () => {
  it("walks every bounded page so nothing past the cap is lost", async () => {
    requestJsonWithHeadersMock
      .mockResolvedValueOnce(pageOf(200, 0, 203, 0, 200))
      .mockResolvedValueOnce(pageOf(3, 200, 203, 200, 200));

    const result = await fetchMyLoanPayments("loan-1");

    expect(requestJsonWithHeadersMock).toHaveBeenCalledTimes(2);
    expect(requestJsonWithHeadersMock).toHaveBeenLastCalledWith(
      "/api/v1/lsp/loans/loan-1/payments?offset=200&limit=200&paginationDetails=ON",
      { signal: undefined },
    );
    expect(result.items).toHaveLength(203);
    expect(result.totalCount).toBe(203);
    expect(result.truncated).toBe(false);
  });

  it("stops early when a short page arrives", async () => {
    requestJsonWithHeadersMock.mockResolvedValueOnce(pageOf(1, 0, 1, 0, 200));

    const result = await fetchMyLoanPayments("loan-1");

    expect(requestJsonWithHeadersMock).toHaveBeenCalledTimes(1);
    expect(result.items).toHaveLength(1);
    expect(result.truncated).toBe(false);
  });

  it("reports truncation instead of silently pretending the history is complete", async () => {
    // Every page claims more rows exist; the safety bound must stop the walk
    // and surface the shortfall.
    requestJsonWithHeadersMock.mockImplementation((_path: string) =>
      Promise.resolve(pageOf(200, 0, 100_000, 0, 200)),
    );

    const result = await fetchMyLoanPayments("loan-1");

    expect(requestJsonWithHeadersMock.mock.calls.length).toBe(25);
    expect(result.items).toHaveLength(25 * 200);
    expect(result.totalCount).toBe(100_000);
    expect(result.truncated).toBe(true);
  });
});
