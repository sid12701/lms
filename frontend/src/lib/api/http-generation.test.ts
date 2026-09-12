import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { requestJson, setRefreshCallback } from "@/lib/api/http-client";
import {
  adoptRemoteIntent,
  advanceAuthGeneration,
  captureAuthIntent,
  resetAuthCoordinatorForTests,
} from "@/features/auth/auth-coordinator";
import { clearStoredSession, saveStoredSession } from "@/lib/api/session-storage";
import type { Session } from "@/features/auth/session-types";

const SESSION_A: Session = {
  user: {
    id: "00000000-0000-4000-8000-000000000001",
    username: "a.user",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  accessToken: "token-A",
  expiresAt: "2099-01-01T00:00:00.000Z",
};

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("http-client generation replay", () => {
  beforeEach(() => {
    resetAuthCoordinatorForTests({ clearStorage: true });
    clearStoredSession();
    saveStoredSession(SESSION_A);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    setRefreshCallback(null);
    clearStoredSession();
    resetAuthCoordinatorForTests({ clearStorage: true });
  });

  it("does not retry a stale 401 for the new identity", async () => {
    const refreshCallback = vi.fn(async () => "fresh-token");
    setRefreshCallback(refreshCallback);
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(async () => {
        // Generation advances (logout/login) while the 401 is being handled.
        advanceAuthGeneration("logout");
        return new Response("unauthorized", { status: 401 });
      })
      .mockResolvedValueOnce(jsonResponse({ ok: true }, 200));
    vi.stubGlobal("fetch", fetchMock);

    // The request was issued under the old generation; after the advance the
    // 401 must NOT trigger refresh/retry for the new identity.
    await expect(requestJson("/api/v1/internal/system/context")).rejects.toMatchObject({
      status: 401,
    });
    expect(refreshCallback).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("retries a current-generation 401 once with the renewed token", async () => {
    const refreshCallback = vi.fn(async () => "fresh-token");
    setRefreshCallback(refreshCallback);
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response("unauthorized", { status: 401 }))
      .mockResolvedValueOnce(jsonResponse({ ok: true }, 200));
    vi.stubGlobal("fetch", fetchMock);

    await expect(requestJson("/api/v1/internal/system/context")).resolves.toEqual({ ok: true });
    expect(refreshCallback).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const retryHeaders = fetchMock.mock.calls[1]?.[1]?.headers as Headers;
    expect(retryHeaders.get("Authorization")).toBe("Bearer fresh-token");
  });

  it("does not redirect a stale 428 to change-password", async () => {
    const assign = vi.fn();
    Object.defineProperty(window, "location", {
      value: { pathname: "/loan-applications", assign },
      writable: true,
      configurable: true,
    });
    const fetchMock = vi.fn().mockImplementationOnce(async () => {
      advanceAuthGeneration("logout");
      return new Response(JSON.stringify({ code: "PASSWORD_CHANGE_REQUIRED" }), { status: 428 });
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(requestJson("/api/v1/internal/home/overview")).rejects.toMatchObject({
      status: 428,
    });
    expect(assign).not.toHaveBeenCalled();
  });

  it("fences 401 replay on full intent: same epoch, different writer never retries", async () => {
    const refreshCallback = vi.fn(async () => "fresh-token");
    setRefreshCallback(refreshCallback);
    const issued = captureAuthIntent();
    const fetchMock = vi.fn().mockImplementationOnce(async () => {
      // A colliding same-epoch writer wins while the 401 is in flight.
      // Epoch-only fencing would miss this; full-intent fencing must not retry.
      adoptRemoteIntent({ generation: issued.generation, intent: "zzzz-winning-writer" });
      expect(captureAuthIntent().generation).toBe(issued.generation);
      return new Response("unauthorized", { status: 401 });
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(requestJson("/api/v1/internal/system/context")).rejects.toMatchObject({
      status: 401,
    });
    expect(refreshCallback).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
