import type { CreateUserInput } from "./types";

export function makeCreateUserInput(overrides: Partial<CreateUserInput> = {}): CreateUserInput {
  return {
    username: "created.user",
    email: "created.user@bhawana.local",
    role: "OPS_USER",
    lspId: null,
    idempotencyKey: "idem-create-user",
    ...overrides,
  };
}
