import { describe, expect, it } from "vitest";
import { formatRoleLabel, formatRoleList } from "./role-labels";

describe("role-labels", () => {
  it("humanizes known roles", () => {
    expect(formatRoleLabel("SYSTEM_ADMIN")).toBe("System admin");
    expect(formatRoleLabel("OPS_USER")).toBe("Ops user");
  });

  it("joins role lists for permission copy", () => {
    expect(formatRoleList(["SYSTEM_ADMIN", "OPS_USER"])).toBe("System admin or Ops user");
  });
});
