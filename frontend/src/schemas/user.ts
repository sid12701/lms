/**
 * Console users + API clients.
 *
 * Refinement: any role in {LSP_UI_READ, LSP_UI_WRITE, LSP_API_CLIENT}
 * MUST carry a non-null lspId (blueprint §6 tenant scoping).
 */
import { z } from "zod";
import { Email, IpCidr, Iso8601, Uuid } from "./common";
import { Role } from "./role";

export const UserStatus = z.enum(["ACTIVE", "DISABLED"]);
export type UserStatus = z.infer<typeof UserStatus>;

const TENANT_SCOPED_ROLES = new Set<Role>(["LSP_UI_READ", "LSP_UI_WRITE", "LSP_API_CLIENT"]);

export const User = z
  .object({
    id: Uuid,
    username: z.string().min(3).max(64),
    email: Email,
    status: UserStatus,
    role: Role,
    lspId: Uuid.nullable(),
    mustChangePassword: z.boolean().default(false),
    createdAt: Iso8601,
    lockedAt: Iso8601.nullable().optional(),
    lockReason: z.string().nullable().optional(),
  })
  .superRefine((u, ctx) => {
    if (TENANT_SCOPED_ROLES.has(u.role) && u.lspId === null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["lspId"],
        message: `role ${u.role} requires a non-null lspId`,
      });
    }
  });
export type User = z.infer<typeof User>;

export const ApiClientStatus = z.enum(["ACTIVE", "DISABLED"]);
export type ApiClientStatus = z.infer<typeof ApiClientStatus>;

export const ApiClient = z.object({
  id: Uuid,
  clientId: z.string().min(8).max(64),
  name: z.string().min(1).max(120),
  lspId: Uuid,
  status: ApiClientStatus,
  createdAt: Iso8601,
  lastUsedAt: Iso8601.nullable(),
  /** ISO timestamp of the last secret rotation; null when never rotated. */
  lastRotatedAt: Iso8601.nullable(),
  ipAllowList: z.array(IpCidr),
});
export type ApiClient = z.infer<typeof ApiClient>;
