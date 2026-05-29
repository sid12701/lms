/**
 * Form schemas for the `/users` dialogs.
 *
 * Sibling-to-the-dialog Zod schemas per the project convention
 * (react-refresh/only-export-components forbids non-component exports from
 * `.tsx` modules).
 *
 * Both create + edit forms share the same role-vs-lspId superRefine as the
 * canonical `User` schema — tenant-scoped roles must carry a non-null
 * `lspId`, internal roles must NOT carry one. The dialogs surface this as a
 * field-level message, matching server behaviour.
 */
import { z } from "zod";
import { Email, Uuid } from "@/schemas/common";
import { Role } from "@/schemas/role";
import { UserStatus } from "@/schemas/user";

const TENANT_SCOPED_ROLES = new Set(["LSP_UI_READ", "LSP_UI_WRITE", "LSP_API_CLIENT"]);

const LSP_SENTINEL_NONE = "__none__";

export const LSP_SELECT_NONE = LSP_SENTINEL_NONE;

function refineRoleLspConsistency(
  ctx: z.RefinementCtx,
  role: z.infer<typeof Role>,
  lspId: string | null,
): void {
  if (TENANT_SCOPED_ROLES.has(role) && lspId === null) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ["lspId"],
      message: "Tenant-scoped roles require an LSP.",
    });
  }
  if (!TENANT_SCOPED_ROLES.has(role) && lspId !== null) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ["lspId"],
      message: "Internal roles cannot carry an LSP.",
    });
  }
}

// ─── Create ──────────────────────────────────────────────────────────────────

export const CreateUserFormSchema = z
  .object({
    username: z
      .string()
      .trim()
      .min(3, "Username must be at least 3 characters.")
      .max(64, "Username must be 64 characters or fewer.")
      .regex(/^[a-z0-9._-]+$/iu, "Use letters, digits, dot, hyphen, or underscore."),
    email: Email.refine((v) => v.length <= 254, {
      message: "Email is too long.",
    }),
    role: Role,
    lspId: Uuid.nullable(),
  })
  .superRefine((v, ctx) => refineRoleLspConsistency(ctx, v.role, v.lspId));

export type CreateUserFormValues = z.infer<typeof CreateUserFormSchema>;

// ─── Edit ────────────────────────────────────────────────────────────────────

export const EditUserFormSchema = z
  .object({
    email: Email.refine((v) => v.length <= 254, {
      message: "Email is too long.",
    }),
    role: Role,
    lspId: Uuid.nullable(),
    status: UserStatus,
  })
  .superRefine((v, ctx) => refineRoleLspConsistency(ctx, v.role, v.lspId));

export type EditUserFormValues = z.infer<typeof EditUserFormSchema>;

export function isTenantScopedRole(role: z.infer<typeof Role>): boolean {
  return TENANT_SCOPED_ROLES.has(role);
}
