import { afterEach, describe, expect, it, vi } from "vitest";

const requestJsonWithHeadersMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/http-client")>();
  return {
    ...actual,
    requestJsonWithHeaders: requestJsonWithHeadersMock,
  };
});

import { listApiClients } from "./api";

function listResult(items: unknown[], headers: Record<string, string> = {}) {
  return { data: items, headers: new Headers(headers) };
}

describe("listApiClients", () => {
  afterEach(() => {
    requestJsonWithHeadersMock.mockReset();
  });

  // M20 — filters and pagination travel to the backend; no local filtering.
  it("sends filters and pagination params, and reads totals from headers", async () => {
    requestJsonWithHeadersMock.mockResolvedValue(
      listResult([], { "X-Total-Count": "42", "X-Limit": "10", "X-Offset": "20" }),
    );

    const result = await listApiClients({
      q: "collect",
      status: "DISABLED",
      lspId: "33333333-3333-3333-3333-333333333333",
      page: 2,
      pageSize: 10,
    });

    const [path] = requestJsonWithHeadersMock.mock.calls[0] as [string];
    const url = new URL(path, "http://localhost");
    expect(url.pathname).toBe("/api/v1/internal/admin/api-clients");
    expect(url.searchParams.get("q")).toBe("collect");
    expect(url.searchParams.get("status")).toBe("INACTIVE");
    expect(url.searchParams.get("lspId")).toBe("33333333-3333-3333-3333-333333333333");
    expect(url.searchParams.get("offset")).toBe("20");
    expect(url.searchParams.get("limit")).toBe("10");
    expect(url.searchParams.get("paginationDetails")).toBe("ON");

    expect(result.total).toBe(42);
    expect(result.page).toBe(2);
    expect(result.pageSize).toBe(10);
    expect(result.items).toEqual([]);
  });

  it("maps backend INACTIVE to DISABLED and falls back to the item count without headers", async () => {
    requestJsonWithHeadersMock.mockResolvedValue(
      listResult([
        {
          id: "11111111-1111-1111-1111-111111111111",
          clientId: "acme-client",
          name: "Acme integration",
          description: null,
          status: "INACTIVE",
          lspId: "33333333-3333-3333-3333-333333333333",
          lspName: "Acme LSP",
          createdAt: "2026-06-01T10:00:00.000Z",
          lastUsedAt: null,
          lastRotatedAt: null,
        },
      ]),
    );

    const result = await listApiClients();

    const [path] = requestJsonWithHeadersMock.mock.calls[0] as [string];
    const url = new URL(path, "http://localhost");
    expect(url.searchParams.get("offset")).toBe("0");
    expect(url.searchParams.get("limit")).toBe("25");
    expect(result.items[0]?.status).toBe("DISABLED");
    expect(result.items[0]?.lspName).toBe("Acme LSP");
    expect(result.total).toBe(1);
  });
});
