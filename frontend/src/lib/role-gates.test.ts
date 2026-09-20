import { describe, expect, it } from "vitest";
import {
  canPostRepayment,
  defaultLandingFor,
  hasAnyRole,
  isInternalUser,
  isLspUiUser,
  primaryRoleFor,
  uiSessionRoles,
} from "./role-gates";

describe("defaultLandingFor (Gap #8)", () => {
  it("routes each role set to its deliberate primary work surface", () => {
    expect(defaultLandingFor(["SYSTEM_ADMIN"])).toBe("/home");
    expect(defaultLandingFor(["OPS_USER"])).toBe("/loan-applications");
    expect(defaultLandingFor(["PRODUCT_ADMIN"])).toBe("/products");
    expect(defaultLandingFor(["LSP_UI_READ"])).toBe("/my-loans");
    expect(defaultLandingFor(["LSP_UI_WRITE"])).toBe("/my-loans");
  });
});

describe("uiSessionRoles (M19)", () => {
  it("keeps every recognized UI role in canonical priority order", () => {
    expect(uiSessionRoles(["LSP_UI_READ", "SYSTEM_ADMIN", "OPS_USER"])).toEqual([
      "SYSTEM_ADMIN",
      "OPS_USER",
      "LSP_UI_READ",
    ]);
  });

  it("drops unknown roles and the machine-only API client role", () => {
    expect(uiSessionRoles(["LSP_API_CLIENT", "tenant-admin", "PRODUCT_ADMIN"])).toEqual([
      "PRODUCT_ADMIN",
    ]);
  });

  it("deduplicates repeated grants", () => {
    expect(uiSessionRoles(["OPS_USER", "OPS_USER"])).toEqual(["OPS_USER"]);
  });

  it("returns [] for an empty or fully-unrecognized claim (fails closed upstream)", () => {
    expect(uiSessionRoles([])).toEqual([]);
    expect(uiSessionRoles(["LSP_API_CLIENT"])).toEqual([]);
  });
});

describe("primaryRoleFor (M19)", () => {
  it("resolves the deliberate landing priority, not the claim order", () => {
    expect(primaryRoleFor(["PRODUCT_ADMIN", "OPS_USER"])).toBe("OPS_USER");
    expect(primaryRoleFor(["LSP_UI_READ", "LSP_UI_WRITE"])).toBe("LSP_UI_WRITE");
    expect(primaryRoleFor(["SYSTEM_ADMIN", "PRODUCT_ADMIN"])).toBe("SYSTEM_ADMIN");
  });

  it("returns null when no UI role is present", () => {
    expect(primaryRoleFor([])).toBeNull();
  });
});

describe("role-set predicates (M19)", () => {
  it("isInternalUser is true for any internal role in the set", () => {
    expect(isInternalUser(["SYSTEM_ADMIN"])).toBe(true);
    expect(isInternalUser(["OPS_USER", "PRODUCT_ADMIN"])).toBe(true);
    expect(isInternalUser(["LSP_UI_READ"])).toBe(false);
    expect(isInternalUser([])).toBe(false);
  });

  it("isLspUiUser is true for LSP browser roles, never API client or empty", () => {
    expect(isLspUiUser(["LSP_UI_READ"])).toBe(true);
    expect(isLspUiUser(["LSP_UI_WRITE"])).toBe(true);
    expect(isLspUiUser(["SYSTEM_ADMIN"])).toBe(false);
    expect(isLspUiUser([])).toBe(false);
  });

  it("hasAnyRole mirrors the backend's any-authority check", () => {
    expect(hasAnyRole(["OPS_USER", "PRODUCT_ADMIN"], ["SYSTEM_ADMIN", "OPS_USER"])).toBe(true);
    expect(hasAnyRole(["PRODUCT_ADMIN"], ["SYSTEM_ADMIN", "OPS_USER"])).toBe(false);
    expect(hasAnyRole([], ["SYSTEM_ADMIN"])).toBe(false);
  });

  it("canPostRepayment follows the role union", () => {
    expect(canPostRepayment(["SYSTEM_ADMIN"])).toBe(true);
    expect(canPostRepayment(["OPS_USER", "PRODUCT_ADMIN"])).toBe(true);
    expect(canPostRepayment(["PRODUCT_ADMIN"])).toBe(false);
    expect(canPostRepayment(["LSP_UI_WRITE"])).toBe(false);
  });
});
