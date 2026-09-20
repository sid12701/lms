import { afterEach, describe, expect, it, vi } from "vitest";
import {
  BUSINESS_TIME_ZONE,
  formatDate,
  formatDateTime,
  formatRelative,
  maskAadhaar,
} from "./format";

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

/**
 * M09 — instants render in the business zone (Asia/Kolkata) and are labeled
 * IST; date-only values are contractual calendar dates and render literally.
 * Both must hold for a browser outside India, so this block runs under a
 * non-India TZ.
 */
describe("business-zone date formatting (M09)", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("declares the backend business zone", () => {
    expect(BUSINESS_TIME_ZONE).toBe("Asia/Kolkata");
  });

  it("renders an instant in IST and labels it, regardless of browser zone", () => {
    vi.stubEnv("TZ", "America/Los_Angeles");
    // 2026-03-10T20:30:00Z = 2026-03-11 02:00 IST = 2026-03-10 13:00 PDT —
    // three different wall-clock readings; only the IST one is correct.
    expect(formatDateTime("2026-03-10T20:30:00.000Z")).toBe("11 Mar 2026, 02:00 IST");
  });

  it("does not shift an early-morning IST instant onto the previous day", () => {
    vi.stubEnv("TZ", "America/Los_Angeles");
    expect(formatDate("2026-03-10T20:30:00.000Z")).toBe("11 Mar 2026");
  });

  it("anchors a leap-day-eve instant on the IST leap day", () => {
    vi.stubEnv("TZ", "Pacific/Auckland");
    // 2024-02-28T20:00:00Z = 2024-02-29 01:30 IST.
    expect(formatDate("2024-02-28T20:00:00.000Z")).toBe("29 Feb 2024");
    expect(formatDateTime("2024-02-28T20:00:00.000Z")).toBe("29 Feb 2024, 01:30 IST");
  });

  it("treats a date-only value as a literal calendar date in any browser zone", () => {
    vi.stubEnv("TZ", "America/Los_Angeles");
    expect(formatDate("2026-05-09")).toBe("09 May 2026");
    vi.stubEnv("TZ", "Pacific/Kiritimati"); // UTC+14 — the extreme east
    expect(formatDate("2026-05-09")).toBe("09 May 2026");
  });
});
