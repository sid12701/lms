import { beforeEach, describe, expect, it, vi } from "vitest";

const requestJsonMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", () => ({
  requestJson: requestJsonMock,
}));

import { listProductOptions } from "./options";

describe("listProductOptions", () => {
  beforeEach(() => {
    requestJsonMock.mockReset();
  });

  it("uses the workflow-safe product options endpoint", async () => {
    const options = [{ id: "product-1", code: "SALARY", name: "Salary", status: "ACTIVE" }];
    requestJsonMock.mockResolvedValue(options);

    await expect(listProductOptions()).resolves.toEqual(options);
    expect(requestJsonMock).toHaveBeenCalledWith("/api/v1/internal/admin/product-options");
  });
});
