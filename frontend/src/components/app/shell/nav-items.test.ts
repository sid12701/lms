import { describe, expect, it } from "vitest";
import { getNavItems } from "./nav-items";

function flatLabels(role: Parameters<typeof getNavItems>[0]): string[] {
  return getNavItems(role).flatMap((group) => group.items.map((item) => item.label));
}

describe("getNavItems (Gap #8)", () => {
  it("shows Home only for SYSTEM_ADMIN", () => {
    expect(flatLabels("SYSTEM_ADMIN")).toContain("Home");
    expect(flatLabels("OPS_USER")).not.toContain("Home");
    expect(flatLabels("PRODUCT_ADMIN")).not.toContain("Home");
    expect(flatLabels("LSP_UI_READ")).not.toContain("Home");
    expect(flatLabels("LSP_UI_WRITE")).not.toContain("Home");
  });

  it("gives OPS_USER loan applications without admin-only entries", () => {
    const labels = flatLabels("OPS_USER");
    expect(labels).toContain("Loan applications");
    expect(labels).toContain("Alerts");
    expect(labels).not.toContain("Reports");
    expect(labels).not.toContain("Users");
  });

  it("gives PRODUCT_ADMIN products plus the shared applications list", () => {
    const labels = flatLabels("PRODUCT_ADMIN");
    expect(labels).toContain("Products");
    expect(labels).toContain("Loan applications");
  });

  it("gives LSP users only My loans", () => {
    for (const role of ["LSP_UI_READ", "LSP_UI_WRITE"] as const) {
      expect(flatLabels(role)).toEqual(["My loans"]);
    }
  });
});
