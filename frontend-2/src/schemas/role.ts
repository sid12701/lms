/**
 * Roles, permissions, and actor-context.
 *
 * Role list pinned to blueprint §6 (six bundles). Permission list pinned
 * to blueprint §6 "minimum permission set". ActorContext mirrors the
 * tenant-isolation contract from blueprint §5 (every request resolves an
 * actor — relied on by audit streams BR-7).
 */
import { z } from "zod";
import { Uuid } from "./common";

export const Role = z.enum([
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_READ",
  "LSP_UI_WRITE",
  "LSP_API_CLIENT",
]);
export type Role = z.infer<typeof Role>;

export const Permission = z.enum([
  "LOAN_READ",
  "LOAN_WRITE",
  "LOAN_STATUS_UPDATE",
  "PRODUCT_READ",
  "PRODUCT_WRITE",
  "USER_READ",
  "USER_WRITE",
  "REPORT_REQUEST",
  "REPORT_READ",
  "WEBHOOK_CONFIG_WRITE",
  "DISBURSEMENT_TRIGGER",
  "FORECLOSURE_TRIGGER",
  "ALL_LSP_VIEW",
]);
export type Permission = z.infer<typeof Permission>;

export const ActorType = z.enum(["INTERNAL_USER", "LSP_UI_USER", "API_CLIENT", "SYSTEM"]);
export type ActorType = z.infer<typeof ActorType>;

export const Channel = z.enum(["UI", "API", "WEBHOOK", "SCHEDULER"]);
export type Channel = z.infer<typeof Channel>;

export const ActorContext = z.object({
  actorType: ActorType,
  actorId: Uuid,
  /** Null for INTERNAL_USER + SYSTEM; required for LSP_UI_USER + API_CLIENT. */
  lspScope: Uuid.nullable(),
  channel: Channel,
});
export type ActorContext = z.infer<typeof ActorContext>;
