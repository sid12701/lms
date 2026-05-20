import { describe, expect, it } from "vitest";
import { ApiClient, User, UserStatus } from "./user";

const UUID = "550e8400-e29b-41d4-a716-446655440000";
const NOW = "2026-05-09T10:00:00.000Z";

function baseUser() {
  return {
    id: UUID,
    username: "alice",
    email: "alice@bhawana.example",
    status: "ACTIVE" as const,
    role: "SYSTEM_ADMIN" as const,
    lspId: null,
    mustChangePassword: false,
    createdAt: NOW,
  };
}

describe("UserStatus", () => {
  it("accepts members and rejects others", () => {
    expect(UserStatus.safeParse("ACTIVE").success).toBe(true);
    expect(UserStatus.safeParse("DISABLED").success).toBe(true);
    expect(UserStatus.safeParse("LOCKED").success).toBe(false);
  });
});

describe("User schema", () => {
  it("accepts an internal user with null lspId", () => {
    expect(User.safeParse(baseUser()).success).toBe(true);
  });

  it("BR-6: tenant-scoped role with null lspId is rejected", () => {
    const u = { ...baseUser(), role: "LSP_UI_READ" as const, lspId: null };
    const r = User.safeParse(u);
    expect(r.success).toBe(false);
    if (!r.success) {
      expect(r.error.issues[0]?.path).toEqual(["lspId"]);
    }
  });

  it("LSP_UI_WRITE with lspId set is accepted", () => {
    const u = { ...baseUser(), role: "LSP_UI_WRITE" as const, lspId: UUID };
    expect(User.safeParse(u).success).toBe(true);
  });

  it("LSP_API_CLIENT requires lspId", () => {
    const u = { ...baseUser(), role: "LSP_API_CLIENT" as const, lspId: null };
    expect(User.safeParse(u).success).toBe(false);
  });

  it("rejects username shorter than 3 chars", () => {
    expect(User.safeParse({ ...baseUser(), username: "ab" }).success).toBe(false);
  });

  it("rejects malformed email", () => {
    expect(User.safeParse({ ...baseUser(), email: "not-email" }).success).toBe(false);
  });

  it("defaults mustChangePassword to false when omitted", () => {
    const u = baseUser() as Partial<ReturnType<typeof baseUser>>;
    delete u.mustChangePassword;
    const parsed = User.safeParse(u);
    expect(parsed.success).toBe(true);
    if (parsed.success) expect(parsed.data.mustChangePassword).toBe(false);
  });
});

describe("ApiClient schema", () => {
  const base = {
    id: UUID,
    clientId: "client-1234",
    name: "Acme LSP API",
    lspId: UUID,
    status: "ACTIVE" as const,
    createdAt: NOW,
    lastUsedAt: null,
    ipAllowList: ["10.0.0.0/8", "192.168.1.1"],
  };

  it("accepts a fully-valid client", () => {
    expect(ApiClient.safeParse(base).success).toBe(true);
  });

  it("rejects clientId shorter than 8 chars", () => {
    expect(ApiClient.safeParse({ ...base, clientId: "short" }).success).toBe(false);
  });

  it("rejects ipAllowList containing IPv6", () => {
    expect(ApiClient.safeParse({ ...base, ipAllowList: ["::1"] }).success).toBe(false);
  });
});
