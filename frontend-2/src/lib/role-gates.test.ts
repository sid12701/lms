import { describe, expect, it } from "vitest";
import { defaultLandingFor } from "./role-gates";

describe("defaultLandingFor (Gap #8)", () => {
  it("routes each role to its primary work surface", () => {
    expect(defaultLandingFor("SYSTEM_ADMIN")).toBe("/home");
    expect(defaultLandingFor("OPS_USER")).toBe("/loan-applications");
    expect(defaultLandingFor("PRODUCT_ADMIN")).toBe("/products");
    expect(defaultLandingFor("LSP_UI_READ")).toBe("/my-loans");
    expect(defaultLandingFor("LSP_UI_WRITE")).toBe("/my-loans");
  });
});
