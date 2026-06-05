import { describe, expect, it } from "vitest";
import { ActorContext, ActorType, Channel, Permission, Role } from "./role";

describe("Role enum", () => {
  it("accepts every blueprint §6 role", () => {
    for (const r of [
      "SYSTEM_ADMIN",
      "OPS_USER",
      "PRODUCT_ADMIN",
      "LSP_UI_READ",
      "LSP_UI_WRITE",
      "LSP_API_CLIENT",
    ]) {
      expect(Role.safeParse(r).success).toBe(true);
    }
  });

  it("rejects roles not in blueprint", () => {
    expect(Role.safeParse("ADMIN").success).toBe(false);
    expect(Role.safeParse("system_admin").success).toBe(false);
  });
});

describe("Permission enum", () => {
  it("accepts the canonical permissions", () => {
    expect(Permission.safeParse("LOAN_READ").success).toBe(true);
    expect(Permission.safeParse("ALL_LSP_VIEW").success).toBe(true);
  });

  it("rejects unknown permissions", () => {
    expect(Permission.safeParse("LOAN_DELETE").success).toBe(false);
    expect(Permission.safeParse("").success).toBe(false);
  });
});

describe("ActorType + Channel enums", () => {
  it("ActorType members are accepted", () => {
    for (const t of ["INTERNAL_USER", "LSP_UI_USER", "API_CLIENT", "SYSTEM"]) {
      expect(ActorType.safeParse(t).success).toBe(true);
    }
    expect(ActorType.safeParse("EXTERNAL").success).toBe(false);
  });

  it("Channel members are accepted", () => {
    for (const c of ["UI", "API", "WEBHOOK", "SCHEDULER"]) {
      expect(Channel.safeParse(c).success).toBe(true);
    }
    expect(Channel.safeParse("EMAIL").success).toBe(false);
  });
});

describe("ActorContext", () => {
  const validUuid = "550e8400-e29b-41d4-a716-446655440000";

  it("accepts an internal user with null lspScope", () => {
    expect(
      ActorContext.safeParse({
        actorType: "INTERNAL_USER",
        actorId: validUuid,
        lspScope: null,
        channel: "UI",
      }).success,
    ).toBe(true);
  });

  it("accepts an LSP UI user with a uuid lspScope", () => {
    expect(
      ActorContext.safeParse({
        actorType: "LSP_UI_USER",
        actorId: validUuid,
        lspScope: validUuid,
        channel: "UI",
      }).success,
    ).toBe(true);
  });

  it("rejects malformed actorId", () => {
    expect(
      ActorContext.safeParse({
        actorType: "SYSTEM",
        actorId: "x",
        lspScope: null,
        channel: "SCHEDULER",
      }).success,
    ).toBe(false);
  });
});
