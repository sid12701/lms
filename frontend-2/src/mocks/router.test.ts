/**
 * Router pipeline tests:
 *   - 404 on unknown path
 *   - permission-denied scenario rejects mutations with FORBIDDEN
 *   - server-error scenario short-circuits any request
 *   - latency timer fires (fake timers)
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import {
  _clearIdempotencyCacheForTests,
  _resetRoutesForTests,
  dispatch,
  registerRoute,
} from "./router";
import { setDb, createEmptyDb } from "./db/state";
import { scenario } from "./scenarios";
import { setLatencyOverride } from "./latency";
import { MockError } from "./errors";

beforeEach(() => {
  _resetRoutesForTests();
  _clearIdempotencyCacheForTests();
  setDb(createEmptyDb());
  scenario.reset();
  setLatencyOverride(0); // no real waiting in tests
});

afterEach(() => {
  scenario.reset();
  setLatencyOverride(null);
});

describe("router.dispatch", () => {
  it("throws NotFoundError when no route matches", async () => {
    await expect(dispatch({ method: "GET", path: "/nope" }, z.unknown())).rejects.toMatchObject({
      code: "NOT_FOUND",
      httpStatus: 404,
    });
  });

  it("rejects mutations with FORBIDDEN under permission-denied scenario", async () => {
    registerRoute("POST", "/x", () => ({ ok: true }), { mutating: true });
    scenario.set("permission-denied");
    await expect(
      dispatch({ method: "POST", path: "/x", body: {} }, z.object({ ok: z.literal(true) })),
    ).rejects.toMatchObject({ code: "FORBIDDEN", httpStatus: 403 });
  });

  it("permission-denied scenario does not block GETs", async () => {
    registerRoute("GET", "/r", () => ({ ok: true }));
    scenario.set("permission-denied");
    await expect(
      dispatch({ method: "GET", path: "/r" }, z.object({ ok: z.literal(true) })),
    ).resolves.toEqual({ ok: true });
  });

  it("server-error scenario short-circuits any request", async () => {
    registerRoute("GET", "/r", () => ({ ok: true }));
    scenario.set("server-error");
    await expect(
      dispatch({ method: "GET", path: "/r" }, z.object({ ok: z.literal(true) })),
    ).rejects.toBeInstanceOf(MockError);
  });

  it("uses fake timers for latency", async () => {
    vi.useFakeTimers();
    setLatencyOverride(500);

    registerRoute("GET", "/timed", () => ({ ok: true }));

    const promise = dispatch({ method: "GET", path: "/timed" }, z.object({ ok: z.literal(true) }));

    // Advance past the latency window.
    await vi.advanceTimersByTimeAsync(500);

    await expect(promise).resolves.toEqual({ ok: true });
    vi.useRealTimers();
  });

  it("path params are extracted from the URL", async () => {
    let captured: Record<string, string> | undefined;
    registerRoute("GET", "/items/:id", (req) => {
      captured = req.params;
      return { id: req.params?.id ?? "" };
    });

    await dispatch({ method: "GET", path: "/items/abc-123" }, z.object({ id: z.string() }));
    expect(captured).toEqual({ id: "abc-123" });
  });

  it("idempotency: same key replays cached value without re-invoking handler", async () => {
    let callCount = 0;
    registerRoute(
      "POST",
      "/m",
      () => {
        callCount += 1;
        return { count: callCount };
      },
      { mutating: true },
    );

    const headers = { "Idempotency-Key": "key-A" };
    const result1 = await dispatch(
      { method: "POST", path: "/m", body: {}, headers },
      z.object({ count: z.number() }),
    );
    const result2 = await dispatch(
      { method: "POST", path: "/m", body: {}, headers },
      z.object({ count: z.number() }),
    );

    expect(result1).toEqual({ count: 1 });
    expect(result2).toEqual({ count: 1 });
    expect(callCount).toBe(1);
  });

  it("schema-drift: parser failure throws", async () => {
    registerRoute("GET", "/bad", () => ({ wrong: "shape" }));
    await expect(
      dispatch({ method: "GET", path: "/bad" }, z.object({ ok: z.literal(true) })),
    ).rejects.toBeInstanceOf(z.ZodError);
  });
});
