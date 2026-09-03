/**
 * LSP (Lending Service Provider) tenant.
 * Multi-tenancy carrier per blueprint §5.
 */
import { z } from "zod";
import { Iso8601, Uuid } from "./common";

export const LspStatus = z.enum(["ACTIVE", "SUSPENDED", "INACTIVE"]);
export type LspStatus = z.infer<typeof LspStatus>;

/** Matches `LspStatusChangeReason` on the backend status API. */
export const LspStatusChangeReason = z.enum([
  "SECURITY_INCIDENT",
  "COMPLIANCE",
  "OFFBOARDING",
  "OPERATIONAL",
]);
export type LspStatusChangeReason = z.infer<typeof LspStatusChangeReason>;

/** Status values accepted by `PUT …/lsps/{id}/status`. */
export const LspOperationalStatus = z.enum(["ACTIVE", "INACTIVE"]);
export type LspOperationalStatus = z.infer<typeof LspOperationalStatus>;

export const Lsp = z.object({
  id: Uuid,
  code: z
    .string()
    .min(2)
    .max(16)
    .regex(/^[A-Z][A-Z0-9_-]*$/u, "code must be uppercase alnum"),
  name: z.string().min(1).max(120),
  status: LspStatus,
  createdAt: Iso8601,
});
export type Lsp = z.infer<typeof Lsp>;
