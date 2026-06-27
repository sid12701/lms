import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { loginPresetForRole } from "./login-role-presets";

describe("loginPresetForRole", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_LOGIN_BOOTSTRAP_PASSWORD", "bootstrap-secret");
    vi.stubEnv("VITE_LOGIN_DEFAULT_PASSWORD", "demo-secret");
    vi.stubEnv("VITE_LOGIN_SYSTEM_ADMIN_EMAIL", "siddhant@bhawanafinance.com");
    vi.stubEnv("VITE_LOGIN_OPS_USER_EMAIL", "ops.reviewer1@bhawana.local");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("returns bootstrap password for SYSTEM_ADMIN", () => {
    expect(loginPresetForRole("SYSTEM_ADMIN")).toEqual({
      email: "siddhant@bhawanafinance.com",
      password: "bootstrap-secret",
    });
  });

  it("returns default password for other roles", () => {
    expect(loginPresetForRole("OPS_USER")).toEqual({
      email: "ops.reviewer1@bhawana.local",
      password: "demo-secret",
    });
  });

  it("returns null when the role email is not configured", () => {
    vi.stubEnv("VITE_LOGIN_OPS_USER_EMAIL", "");
    expect(loginPresetForRole("OPS_USER")).toBeNull();
  });
});
