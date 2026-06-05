import { z } from "zod";
import { Uuid } from "@/schemas/common";
import { Role } from "@/schemas/role";

export const SessionUser = z.object({
  id: Uuid,
  username: z.string().min(3).max(64),
  role: Role,
  lspId: Uuid.nullable(),
  mustChangePassword: z.boolean(),
});
export type SessionUser = z.infer<typeof SessionUser>;

export const Session = z.object({
  user: SessionUser,
  accessToken: z.string().min(1),
  expiresAt: z.string().datetime({ offset: true }),
});
export type Session = z.infer<typeof Session>;

export const SessionOrNull = Session.nullable();
