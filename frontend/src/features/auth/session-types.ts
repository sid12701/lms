import { z } from "zod";
import { Uuid } from "@/schemas/common";
import { Role } from "@/schemas/role";

export const SessionUser = z
  .object({
    id: Uuid,
    username: z.string().min(3).max(64),
    /**
     * Primary role — drives the post-login landing route and the actor label
     * shown in the shell. It is deliberately NOT the authorization set:
     * capability checks (nav, route guards, actions) must read `roles`.
     */
    role: Role,
    /**
     * M19 — the full set of recognized UI roles granted by the backend
     * `roles` claim (sorted by UI priority at session build). Every
     * authorization check — nav items, route guards, action affordances,
     * storage identity, query-client scope — evaluates this set, so a
     * multi-role user (e.g. OPS_USER + PRODUCT_ADMIN) keeps every surface.
     * Optional on input only for backward compatibility with persisted
     * session metadata written before `roles` existed; the transform derives
     * `[role]` for those records. Empty arrays still fail closed (min 1).
     */
    roles: z.array(Role).min(1).optional(),
    lspId: Uuid.nullable(),
    lspName: z.string().nullable().optional(),
    mustChangePassword: z.boolean(),
  })
  .transform((user) => ({ ...user, roles: user.roles ?? [user.role] }));
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
