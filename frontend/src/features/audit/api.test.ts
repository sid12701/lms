import { afterEach, describe, expect, it, vi } from "vitest";

const requestJsonMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", () => ({
  buildQueryPath: (path: string, params: Record<string, string | number | undefined>) => {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== "") search.set(key, String(value));
    }
    const qs = search.toString();
    return qs ? `${path}?${qs}` : path;
  },
  requestJson: requestJsonMock,
}));

import { fetchAuditEvents } from "./api";

describe("fetchAuditEvents", () => {
  afterEach(() => {
    requestJsonMock.mockReset();
  });

  it("sends correlationId, loanApplicationId, and default since window to the backend", async () => {
    requestJsonMock.mockResolvedValue({
      items: [],
      nextCursor: null,
      limit: 25,
    });

    await fetchAuditEvents({
      correlationId: "corr-deep",
      loanApplicationId: "11111111-1111-4111-8111-111111111111",
      pageSize: 25,
    });

    expect(requestJsonMock).toHaveBeenCalledOnce();
    const path = requestJsonMock.mock.calls[0]?.[0] as string;
    expect(path).toContain("correlationId=corr-deep");
    expect(path).toContain("loanApplicationId=11111111-1111-4111-8111-111111111111");
    expect(path).toContain("since=");
    expect(path).toContain("limit=25");
  });

  it("returns backend rows without client-side post-filtering", async () => {
    requestJsonMock.mockResolvedValue({
      items: [
        {
          id: "APPLICATION:1",
          stream: "APPLICATION",
          occurredAt: "2026-06-07T10:00:00Z",
          actorUsername: "alice.ops",
          loanApplicationId: "11111111-1111-4111-8111-111111111111",
          borrowerId: null,
          lspId: null,
          productId: null,
          action: "STATUS_TRANSITION",
          summary: "Approved",
          detail: {},
          correlationId: "corr-deep",
        },
      ],
      nextCursor: "cursor-token",
      limit: 25,
    });

    const result = await fetchAuditEvents({
      correlationId: "corr-deep",
      pageSize: 25,
    });

    expect(result.items).toHaveLength(1);
    expect(result.items[0]?.correlationId).toBe("corr-deep");
    expect(result.nextCursor).toBe("cursor-token");
  });
});
