import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { loginPresetForRole } from "./login-role-presets";

describe("loginPresetForRole", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_LOGIN_SYSTEM_ADMIN_EMAIL", "siddhant@bhawanafinance.com");
    vi.stubEnv("VITE_LOGIN_OPS_USER_EMAIL", "ops.reviewer1@bhawana.local");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("returns the configured email for SYSTEM_ADMIN", () => {
    expect(loginPresetForRole("SYSTEM_ADMIN")).toEqual({
      email: "siddhant@bhawanafinance.com",
    });
  });

  it("returns the configured email for other roles", () => {
    expect(loginPresetForRole("OPS_USER")).toEqual({
      email: "ops.reviewer1@bhawana.local",
    });
  });

  it("returns null when the role email is not configured", () => {
    vi.stubEnv("VITE_LOGIN_OPS_USER_EMAIL", "");
    expect(loginPresetForRole("OPS_USER")).toBeNull();
  });
});
