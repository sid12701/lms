/**
 * Test helpers for internal-session API transport tests (#78).
 * Sets localStorage + in-memory session without mocking session-storage.
 */
import { saveStoredSession } from "@/lib/api/session-storage";
import type { Session } from "@/features/auth/session-types";

const DEFAULT_INTERNAL_SESSION: Session = {
  user: {
    id: "22222222-2222-4222-8222-222222222222",
    username: "ops.admin",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  accessToken: "access-token",
  expiresAt: "2099-01-01T00:00:00.000Z",
};

export function saveInternalSession(overrides: Partial<Session> = {}): void {
  saveStoredSession({
    ...DEFAULT_INTERNAL_SESSION,
    ...overrides,
    user: { ...DEFAULT_INTERNAL_SESSION.user, ...overrides.user },
  });
}

export function saveLspSession(): void {
  saveStoredSession({
    user: {
      id: "33333333-3333-4333-8333-333333333333",
      username: "lsp.demo",
      role: "LSP_UI_READ",
      lspId: "11111111-1111-4111-8111-111111111111",
      mustChangePassword: false,
    },
    accessToken: "lsp-access-token",
    expiresAt: "2099-01-01T00:00:00.000Z",
  });
}
