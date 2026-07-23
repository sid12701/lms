import type { Session } from "@/features/auth/session-types";

const TEST_ADMIN_USER_ID = "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa";
export const TEST_OPS_USER_ID = "aaaaaaaa-2222-4aaa-8aaa-aaaaaaaaaaaa";
const TEST_LSP_READ_USER_ID = "aaaaaaaa-4444-4aaa-8aaa-aaaaaaaaaaaa";
const TEST_TEMP_USER_ID = "aaaaaaaa-6666-4aaa-8aaa-aaaaaaaaaaaa";
const TEST_LSP_ID = "00000000-0000-4000-8000-000000000099";

export const adminSession: Session = {
  user: {
    id: TEST_ADMIN_USER_ID,
    username: "ops.admin",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  accessToken: "test.access.token",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

export const lspReadSession: Session = {
  user: {
    id: TEST_LSP_READ_USER_ID,
    username: "lsp.read1",
    role: "LSP_UI_READ",
    lspId: TEST_LSP_ID,
    mustChangePassword: false,
  },
  accessToken: "test.access.token",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

export const tempPasswordSession: Session = {
  user: {
    id: TEST_TEMP_USER_ID,
    username: "temp.user",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: true,
  },
  accessToken: "test.access.token",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};
