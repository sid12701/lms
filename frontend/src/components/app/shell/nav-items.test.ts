import { describe, expect, it } from "vitest";
import { getNavItems } from "./nav-items";
import type { Role } from "@/types";

function flatLabels(roles: readonly Role[]): string[] {
  return getNavItems(roles).flatMap((group) => group.items.map((item) => item.label));
}

describe("getNavItems (Gap #8)", () => {
  it("shows Home only for SYSTEM_ADMIN", () => {
    expect(flatLabels(["SYSTEM_ADMIN"])).toContain("Home");
    expect(flatLabels(["OPS_USER"])).not.toContain("Home");
    expect(flatLabels(["PRODUCT_ADMIN"])).not.toContain("Home");
    expect(flatLabels(["LSP_UI_READ"])).not.toContain("Home");
    expect(flatLabels(["LSP_UI_WRITE"])).not.toContain("Home");
  });

  it("gives OPS_USER loan applications without admin-only entries", () => {
    const labels = flatLabels(["OPS_USER"]);
    expect(labels).toContain("Loan applications");
    expect(labels).toContain("Alerts");
    expect(labels).not.toContain("Reports");
    expect(labels).not.toContain("Users");
  });

  it("gives PRODUCT_ADMIN only the backend-supported products workspace", () => {
    const labels = flatLabels(["PRODUCT_ADMIN"]);
    expect(labels).toContain("Products");
    expect(labels).not.toContain("Loan applications");
    expect(labels).not.toContain("Borrowers");
  });

  it("gives LSP users only the My loans workspace", () => {
    for (const role of ["LSP_UI_READ", "LSP_UI_WRITE"] as const) {
      expect(flatLabels([role])).toEqual(["My loans"]);
    }
  });

  it("M19: OPS_USER + PRODUCT_ADMIN sees the union of both role surfaces", () => {
    const labels = flatLabels(["OPS_USER", "PRODUCT_ADMIN"]);
    // OPS surface.
    expect(labels).toContain("Loan applications");
    expect(labels).toContain("Borrowers");
    expect(labels).toContain("Alerts");
    // The audit regression: product nav must not disappear for multi-role ops.
    expect(labels).toContain("Products");
    // Still no admin-only entries — the union never widens beyond granted roles.
    expect(labels).not.toContain("Home");
    expect(labels).not.toContain("Users");
    expect(labels).not.toContain("Reports");
  });

  it("M19: LSP + internal mixed roles see both workspaces", () => {
    const labels = flatLabels(["OPS_USER", "LSP_UI_READ"]);
    expect(labels).toContain("My loans");
    expect(labels).toContain("Loan applications");
  });

  it("M19: empty role set produces no nav entries", () => {
    expect(getNavItems([])).toEqual([{ label: "Workspace", items: [] }]);
  });
});
