import { describe, expect, it } from "vitest";
import { formatRelative, maskAadhaar } from "./format";

describe("maskAadhaar (gap-fixes.md § Gap #1)", () => {
  it("returns the doc-spec shape: 8 X's + last 4 digits", () => {
    expect(maskAadhaar("123412341234")).toBe("XXXXXXXX1234");
  });

  it("is idempotent on already-masked input", () => {
    expect(maskAadhaar("XXXXXXXX1234")).toBe("XXXXXXXX1234");
  });

  it("strips non-digit separators before taking the last 4", () => {
    expect(maskAadhaar("1234 5678 9012")).toBe("XXXXXXXX9012");
    expect(maskAadhaar("1234-5678-9012")).toBe("XXXXXXXX9012");
  });

  it("handles null/undefined/empty defensively", () => {
    expect(maskAadhaar("")).toBe("");
    expect(maskAadhaar(null)).toBe("");
    expect(maskAadhaar(undefined)).toBe("");
  });
});

describe("formatRelative", () => {
  it("returns an em dash for implausibly old timestamps", () => {
    expect(formatRelative("1970-01-01T00:00:00.000Z")).toBe("—");
  });

  it("returns relative copy for plausible timestamps", () => {
    const now = new Date("2026-06-21T12:00:00.000Z");
    expect(formatRelative("2026-06-21T10:00:00.000Z", now)).toBe("2 hours ago");
  });
});
