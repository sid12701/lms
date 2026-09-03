import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SKELETON_DELAY_MS, useDelayedFlag } from "./use-delayed-flag";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useDelayedFlag", () => {
  it("stays false while the load is still inside the delay window", () => {
    const { result } = renderHook(() => useDelayedFlag(true));

    expect(result.current).toBe(false);
    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS - 1);
    });
    expect(result.current).toBe(false);
  });

  it("flips true once the delay has elapsed", () => {
    const { result } = renderHook(() => useDelayedFlag(true));

    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });
    expect(result.current).toBe(true);
  });

  it("never flips true for a load that resolved under the threshold", () => {
    const { result, rerender } = renderHook(({ active }) => useDelayedFlag(active), {
      initialProps: { active: true },
    });

    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS - 100);
    });
    rerender({ active: false });
    act(() => {
      vi.advanceTimersByTime(1_000);
    });

    expect(result.current).toBe(false);
  });

  it("resets when the load finishes after the flag was already raised", () => {
    const { result, rerender } = renderHook(({ active }) => useDelayedFlag(active), {
      initialProps: { active: true },
    });

    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });
    expect(result.current).toBe(true);

    rerender({ active: false });
    expect(result.current).toBe(false);
  });

  it("restarts the delay for a second load rather than reusing the first verdict", () => {
    const { result, rerender } = renderHook(({ active }) => useDelayedFlag(active), {
      initialProps: { active: true },
    });

    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });
    rerender({ active: false });
    rerender({ active: true });

    expect(result.current).toBe(false);
    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });
    expect(result.current).toBe(true);
  });

  it("honours a caller-supplied delay", () => {
    const { result } = renderHook(() => useDelayedFlag(true, 1_000));

    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });
    expect(result.current).toBe(false);
    act(() => {
      vi.advanceTimersByTime(1_000 - SKELETON_DELAY_MS);
    });
    expect(result.current).toBe(true);
  });
});
