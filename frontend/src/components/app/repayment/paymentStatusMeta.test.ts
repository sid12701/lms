import { describe, expect, it } from "vitest";
import { resolvePaymentStatusMeta } from "./paymentStatusMeta";

describe("resolvePaymentStatusMeta", () => {
  it("maps POSTED to a human label", () => {
    expect(resolvePaymentStatusMeta("POSTED")).toEqual({
      label: "Posted",
      intent: "success",
    });
  });

  it("surfaces drift as Unknown (raw)", () => {
    expect(resolvePaymentStatusMeta("SETTLED")).toEqual({
      label: "Unknown (SETTLED)",
      intent: "neutral",
    });
  });
});
