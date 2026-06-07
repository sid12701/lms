import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, requestJson } from "@/lib/api/http-client";

describe("http-client rate limiting", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
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
});
