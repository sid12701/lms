import { describe, expect, it } from "vitest";
import { FILTER_CONTROL_CLASS, filterControlClass } from "./filter-control";

describe("filterControlClass", () => {
  it("returns just the shared control baseline when unset", () => {
    expect(filterControlClass(false)).toBe(FILTER_CONTROL_CLASS);
  });

  it("does not carry any applied-state channel when unset", () => {
    const unset = filterControlClass(false);
    expect(unset).not.toContain("border-primary");
    expect(unset).not.toContain("bg-primary/10");
    expect(unset).not.toContain("text-primary-tinted");
    expect(unset).not.toContain("font-medium");
  });

  it("carries more than a colour change when applied (fixes the 'colour alone' a11y bug)", () => {
    const applied = filterControlClass(true);
    // A colour-only fix would swap text colour alone — that's exactly the bug
    // the docblock says this closes. Pin a border channel and a weight channel
    // alongside the colour ones, so the applied state still reads under
    // greyscale rendering or colour-vision deficiency.
    expect(applied).toContain("border-primary");
    expect(applied).toContain("bg-primary/10");
    expect(applied).toContain("text-primary-tinted");
    expect(applied).toContain("font-medium");
  });

  it("merges a caller-supplied className alongside the control baseline in both states", () => {
    expect(filterControlClass(false, "w-40")).toContain("w-40");
    expect(filterControlClass(true, "w-40")).toContain("w-40");
  });
});
