import { beforeEach, describe, expect, it, vi } from "vitest";

const requestJsonMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", () => ({
  requestJson: requestJsonMock,
}));

import { listProducts } from "./api";

describe("listProducts", () => {
  beforeEach(() => {
    requestJsonMock.mockReset();
  });

  it("loads products and all mapping summaries with one HTTP request", async () => {
    requestJsonMock.mockResolvedValue([
      {
        id: "product-1",
        code: "SALARY-PLUS",
        name: "Salary Plus",
        minPrincipal: 5_000,
        maxPrincipal: 250_000,
        interestRate: 18.5,
        processingFeeRate: 2.25,
        minTenureMonths: 6,
        maxTenureMonths: 24,
        status: "ACTIVE",
        createdAt: "2026-07-18T00:00:00Z",
        mappedLsps: [
          { id: "lsp-1", code: "APEX", name: "Apex Finance", status: "ACTIVE" },
          { id: "lsp-2", code: "NORTH", name: "North Finance", status: "ACTIVE" },
        ],
      },
    ]);

    const result = await listProducts({ page: 0, pageSize: 20 });

    expect(requestJsonMock).toHaveBeenCalledTimes(1);
    expect(requestJsonMock).toHaveBeenCalledWith("/api/v1/internal/admin/products");
    expect(result.items[0]).toMatchObject({
      id: "product-1",
      lspIds: ["lsp-1", "lsp-2"],
      lspNames: ["Apex Finance", "North Finance"],
    });
  });
});
