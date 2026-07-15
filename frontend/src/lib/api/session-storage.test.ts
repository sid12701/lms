import { afterEach, describe, expect, it } from "vitest";
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
});
