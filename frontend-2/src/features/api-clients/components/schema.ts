import { z } from "zod";
import { ApiClientStatus } from "@/schemas/user";
import { IpCidr } from "@/schemas/common";

/**
 * Validation schema for the create-API-client dialog.
 *
 * The IP allow-list is validated entry-wise against the shared `IpCidr`
 * regex (IPv4 + optional /N CIDR). The list itself may be empty — admins
 * can launch a client without restricting source IPs and tighten the
 * allow-list later via the edit dialog.
 */
export const CreateApiClientSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(120, "Name must be 120 characters or fewer."),
  lspId: z.string().uuid("Pick an LSP."),
  ipAllowList: z.array(IpCidr),
});
export type CreateApiClientValues = z.infer<typeof CreateApiClientSchema>;

/**
 * Validation schema for the edit-API-client dialog. Every field is
 * optional in the body, but the form supplies name + status + ipAllowList
 * unconditionally — the optionality matches the wire shape so PATCH
 * mutations stay omittable when the operator only flipped one field.
 */
export const EditApiClientSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(120, "Name must be 120 characters or fewer."),
  status: ApiClientStatus,
  ipAllowList: z.array(IpCidr),
});
export type EditApiClientValues = z.infer<typeof EditApiClientSchema>;
