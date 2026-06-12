import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, requestBlob, requestJson, setRefreshCallback } from "@/lib/api/http-client";

describe("http-client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setRefreshCallback(null);
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
