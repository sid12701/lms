import { describe, expect, it } from "vitest";
import { delinquencyBucketLabel, delinquencyBucketShortLabel } from "./delinquency-display";

describe("delinquencyBucketLabel", () => {
  it("labels every backend bucket code", () => {
    expect(delinquencyBucketLabel("CURRENT")).toBe("Current (0 DPD)");
    expect(delinquencyBucketLabel("DPD_1_30")).toBe("1–30 DPD");
    expect(delinquencyBucketLabel("DPD_31_60")).toBe("31–60 DPD");
    expect(delinquencyBucketLabel("DPD_61_90")).toBe("61–90 DPD");
    expect(delinquencyBucketLabel("DPD_90_PLUS")).toBe("90+ DPD");
  });

  it("labels the frontend schema spelling of the same buckets identically", () => {
    expect(delinquencyBucketLabel("B0")).toBe(delinquencyBucketLabel("CURRENT"));
    expect(delinquencyBucketLabel("B1_30")).toBe(delinquencyBucketLabel("DPD_1_30"));
    expect(delinquencyBucketLabel("B31_60")).toBe(delinquencyBucketLabel("DPD_31_60"));
    expect(delinquencyBucketLabel("B61_90")).toBe(delinquencyBucketLabel("DPD_61_90"));
    expect(delinquencyBucketLabel("B90_PLUS")).toBe(delinquencyBucketLabel("DPD_90_PLUS"));
  });

  it("trims surrounding whitespace before matching", () => {
    expect(delinquencyBucketLabel("  DPD_31_60 ")).toBe("31–60 DPD");
  });

  it("degrades an unmapped bucket to readable text instead of dropping it", () => {
    expect(delinquencyBucketLabel("DPD_180_PLUS")).toBe("DPD 180 PLUS");
  });
});

describe("delinquencyBucketShortLabel", () => {
  /*
   * The boundaries must match the backend's
   * `LoanDelinquencySupport.resolveDelinquencyBucket` exactly: 1–30, 31–60,
   * 61–90, 90+. The chart previously read 0-30 / 30-60 / 60-90, which put
   * every boundary day in two buckets at once.
   */
  it("uses the backend's bucket boundaries, with no day in two buckets", () => {
    expect(delinquencyBucketShortLabel("B0")).toBe("Current");
    expect(delinquencyBucketShortLabel("B1_30")).toBe("1–30");
    expect(delinquencyBucketShortLabel("B31_60")).toBe("31–60");
    expect(delinquencyBucketShortLabel("B61_90")).toBe("61–90");
    expect(delinquencyBucketShortLabel("B90_PLUS")).toBe("90+");
  });

  it("stays consistent with the full label's range", () => {
    for (const bucket of ["B1_30", "B31_60", "B61_90", "B90_PLUS"] as const) {
      expect(delinquencyBucketLabel(bucket)).toBe(`${delinquencyBucketShortLabel(bucket)} DPD`);
    }
  });

  it("degrades an unmapped bucket the same way the full label does", () => {
    expect(delinquencyBucketShortLabel("DPD_180_PLUS")).toBe("DPD 180 PLUS");
  });
});
