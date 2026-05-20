/**
 * Scenario state-machine tests.
 */
import { afterEach, describe, expect, it } from "vitest";
import { scenario } from "./scenarios";
import { getLatencyOverride } from "./latency";

afterEach(() => {
  scenario.reset();
});

describe("scenario", () => {
  it("defaults to happy", () => {
    expect(scenario.current).toBe("happy");
    expect(scenario.shouldFail({ method: "POST", path: "/x" })).toEqual({ fail: false });
  });

  it("permission-denied fails mutations only", () => {
    scenario.set("permission-denied");
    expect(scenario.shouldFail({ method: "POST", path: "/x" })).toMatchObject({
      fail: true,
      kind: "FORBIDDEN",
    });
    expect(scenario.shouldFail({ method: "GET", path: "/x" })).toEqual({ fail: false });
  });

  it("server-error fails everything", () => {
    scenario.set("server-error");
    expect(scenario.shouldFail({ method: "GET", path: "/x" })).toMatchObject({
      fail: true,
      kind: "INTERNAL_SERVER_ERROR",
    });
  });

  it("slow-network sets a latency override", () => {
    scenario.set("slow-network");
    expect(getLatencyOverride()).toBeGreaterThan(1000);
    scenario.set("happy");
    expect(getLatencyOverride()).toBeNull();
  });

  it("failNext arms a one-shot fail and resets afterward", () => {
    scenario.failNext();
    const first = scenario.shouldFail({ method: "POST", path: "/x" });
    const second = scenario.shouldFail({ method: "POST", path: "/x" });
    expect(first.fail).toBe(true);
    expect(second.fail).toBe(false);
  });
});
