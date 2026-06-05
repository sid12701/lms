import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { loginPresetForRole } from "./login-role-presets";

describe("loginPresetForRole", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_LOGIN_BOOTSTRAP_PASSWORD", "bootstrap-secret");
    vi.stubEnv("VITE_LOGIN_DEFAULT_PASSWORD", "demo-secret");
    vi.stubEnv("VITE_LOGIN_SYSTEM_ADMIN_USERNAME", "ops.admin");
    vi.stubEnv("VITE_LOGIN_OPS_USER_USERNAME", "ops.reviewer1");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("returns bootstrap password for SYSTEM_ADMIN", () => {
    expect(loginPresetForRole("SYSTEM_ADMIN")).toEqual({
      username: "ops.admin",
      password: "bootstrap-secret",
    });
  });

  it("returns default password for other roles", () => {
    expect(loginPresetForRole("OPS_USER")).toEqual({
      username: "ops.reviewer1",
      password: "demo-secret",
    });
  });

  it("returns null when the role username is not configured", () => {
    vi.stubEnv("VITE_LOGIN_OPS_USER_USERNAME", "");
    expect(loginPresetForRole("OPS_USER")).toBeNull();
  });
});
