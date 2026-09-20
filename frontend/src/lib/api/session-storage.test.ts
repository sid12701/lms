import { afterEach, describe, expect, it, vi } from "vitest";
import type { Session } from "@/features/auth/session-types";
import {
  SESSION_STORAGE_KEY,
  clearStoredSession,
  getStoredAccessToken,
  loadStoredSession,
  saveStoredSession,
} from "@/lib/api/session-storage";

const SESSION: Session = {
  user: {
    id: "00000000-0000-4000-8000-000000000001",
    username: "ops.admin",
    role: "SYSTEM_ADMIN",
    roles: ["SYSTEM_ADMIN"],
    lspId: null,
    mustChangePassword: false,
  },
  accessToken: "super-secret-access-token",
  expiresAt: "2099-01-01T00:00:00.000Z",
};

describe("session-storage", () => {
  afterEach(() => {
    clearStoredSession();
  });

  it("never persists the access token to localStorage", () => {
    saveStoredSession(SESSION);

    const raw = window.localStorage.getItem(SESSION_STORAGE_KEY);
    expect(raw).toBeTruthy();
    expect(raw).not.toContain("super-secret-access-token");
    expect(raw).not.toContain("accessToken");
    expect(getStoredAccessToken()).toBe("super-secret-access-token");
  });

  it("discards a legacy persisted accessToken and does not rehydrate it", () => {
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        user: SESSION.user,
        expiresAt: SESSION.expiresAt,
        accessToken: "legacy-token-must-die",
      }),
    );

    const loaded = loadStoredSession();
    expect(loaded?.user.username).toBe("ops.admin");
    expect(getStoredAccessToken()).toBeNull();

    const rewritten = window.localStorage.getItem(SESSION_STORAGE_KEY);
    expect(rewritten).toBeTruthy();
    expect(rewritten).not.toContain("legacy-token-must-die");
    expect(rewritten).not.toContain("accessToken");
  });

  it("never pairs A memory bearer with B persisted metadata (cross-session matrix)", () => {
    const userA = SESSION.user;
    const userB = {
      ...SESSION.user,
      id: "00000000-0000-4000-8000-000000000002",
      username: "b.user",
    };
    // A in memory.
    saveStoredSession({ ...SESSION, user: userA, accessToken: "bearer-A" });
    // B persisted to disk (simulates cross-tab B login overwriting storage).
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ user: userB, expiresAt: SESSION.expiresAt }),
    );

    const loaded = loadStoredSession();
    expect(loaded?.user.id).toBe(userB.id);
    // Must NOT expose A's bearer under B's identity — forces refresh path.
    expect(loaded?.accessToken).toBe("");
    expect(getStoredAccessToken()).toBeNull();
  });

  it("returns B disk metadata with empty token when memory is empty", () => {
    clearStoredSession();
    const userB = { ...SESSION.user, id: "00000000-0000-4000-8000-000000000003" };
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ user: userB, expiresAt: SESSION.expiresAt }),
    );

    const loaded = loadStoredSession();
    expect(loaded?.user.id).toBe(userB.id);
    expect(loaded?.accessToken).toBe("");
    expect(getStoredAccessToken()).toBeNull();
  });

  it("keeps the bearer only for same-identity renewal", () => {
    saveStoredSession({ ...SESSION, accessToken: "bearer-A" });
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ user: SESSION.user, expiresAt: SESSION.expiresAt }),
    );

    const loaded = loadStoredSession();
    expect(loaded?.user.id).toBe(SESSION.user.id);
    expect(loaded?.accessToken).toBe("bearer-A");
    expect(getStoredAccessToken()).toBe("bearer-A");
  });

  it("does not pair the bearer across a role-only change with the same id", () => {
    saveStoredSession({ ...SESSION, accessToken: "bearer-A" });
    const demoted = {
      ...SESSION.user,
      role: "LSP_UI_READ" as const,
      roles: ["LSP_UI_READ" as const],
    };
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ user: demoted, expiresAt: SESSION.expiresAt }),
    );

    const loaded = loadStoredSession();
    expect(loaded?.user.role).toBe("LSP_UI_READ");
    expect(loaded?.accessToken).toBe("");
    expect(getStoredAccessToken()).toBeNull();
  });

  it("M19: does not pair the bearer when the role SET changes under a stable primary role", () => {
    saveStoredSession({ ...SESSION, accessToken: "bearer-A" });
    // Same id + same primary role (SYSTEM_ADMIN), but the effective grant set
    // shrank — the old bearer must not ride the reduced authorization scope.
    const narrowed = { ...SESSION.user, roles: ["SYSTEM_ADMIN" as const, "OPS_USER" as const] };
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ user: narrowed, expiresAt: SESSION.expiresAt }),
    );

    const loaded = loadStoredSession();
    expect(loaded?.user.roles).toEqual(["SYSTEM_ADMIN", "OPS_USER"]);
    expect(loaded?.accessToken).toBe("");
    expect(getStoredAccessToken()).toBeNull();
  });

  it("M19: legacy persisted metadata without `roles` rehydrates as [role]", () => {
    const { roles: _dropped, ...legacyUser } = SESSION.user;
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ user: legacyUser, expiresAt: SESSION.expiresAt }),
    );

    const loaded = loadStoredSession();
    expect(loaded?.user.role).toBe("SYSTEM_ADMIN");
    expect(loaded?.user.roles).toEqual(["SYSTEM_ADMIN"]);
  });

  it("does not pair the bearer across an LSP-only change with the same id", () => {
    const lspUser = {
      ...SESSION.user,
      role: "LSP_UI_READ" as const,
      roles: ["LSP_UI_READ" as const],
      lspId: "00000000-0000-4000-8000-000000000099",
    };
    saveStoredSession({ ...SESSION, user: lspUser, accessToken: "bearer-A" });
    const moved = { ...lspUser, lspId: "00000000-0000-4000-8000-000000000077" };
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ user: moved, expiresAt: SESSION.expiresAt }),
    );

    const loaded = loadStoredSession();
    expect(loaded?.user.lspId).toBe("00000000-0000-4000-8000-000000000077");
    expect(loaded?.accessToken).toBe("");
    expect(getStoredAccessToken()).toBeNull();
  });

  it("clears stale memory when disk metadata is absent (foreign logout), not resurrect A", () => {
    saveStoredSession({ ...SESSION, accessToken: "bearer-A" });
    // Remote tab logged out: disk key removed while this tab holds memory.
    window.localStorage.removeItem(SESSION_STORAGE_KEY);

    expect(loadStoredSession()).toBeNull();
    expect(getStoredAccessToken()).toBeNull();
    // Memory was dropped, not kept for a later resurrection.
    expect(loadStoredSession()).toBeNull();
  });

  it("fails closed when storage is unavailable", () => {
    saveStoredSession({ ...SESSION, accessToken: "bearer-A" });
    const getItem = vi.spyOn(window.localStorage, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });
    try {
      expect(loadStoredSession()).toBeNull();
      expect(getStoredAccessToken()).toBeNull();
    } finally {
      getItem.mockRestore();
    }
    // Unavailable storage dropped the in-memory bearer; a later healthy
    // read cannot resurrect it.
    window.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ user: SESSION.user, expiresAt: SESSION.expiresAt }),
    );
    expect(getStoredAccessToken()).toBeNull();
  });
});
