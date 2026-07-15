import { z } from "zod";
import { Uuid } from "@/schemas/common";
import { Role } from "@/schemas/role";

export const SessionUser = z.object({
  id: Uuid,
  username: z.string().min(3).max(64),
  role: Role,
  lspId: Uuid.nullable(),
  lspName: z.string().nullable().optional(),
  mustChangePassword: z.boolean(),
});
export type SessionUser = z.infer<typeof SessionUser>;

export const Session = z.object({
  user: SessionUser,
  /**
   * Bearer access token. Present after login/refresh; may be empty briefly on
   * hard-reload before the httpOnly refresh cookie mints a new token. Never
   * persisted to localStorage (see session-storage.ts / Spec S11).
   */
  accessToken: z.string(),
  expiresAt: z.string().datetime({ offset: true }),
});
export type Session = z.infer<typeof Session>;

export const SessionOrNull = Session.nullable();
