import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { useColumnVisibility } from "./use-column-visibility";

describe("useColumnVisibility", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("keeps known boolean preferences and ignores unknown persisted keys", () => {
    window.localStorage.setItem(
      "loan-columns",
      JSON.stringify({ status: true, removedColumn: false, invalid: "yes" }),
    );

    const { result } = renderHook(() =>
      useColumnVisibility("loan-columns", { status: false, product: true }),
    );

    expect(result.current[0]).toEqual({ status: true, product: true });
  });

  it("persists updates while returning the new in-memory state", () => {
    const { result } = renderHook(() =>
      useColumnVisibility("loan-columns", { status: false, product: true }),
    );

    act(() => {
      result.current[1]({ status: true, product: false });
    });

    expect(result.current[0]).toEqual({ status: true, product: false });
    expect(window.localStorage.getItem("loan-columns")).toBe(
      JSON.stringify({ status: true, product: false }),
    );
  });
});
