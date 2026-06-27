import { z } from "zod";
import { ApiClientStatus } from "@/schemas/user";
/**
 * Validation schema for the create-API-client dialog.
 *
 * IP allowlists are managed per LSP (UI vs API surfaces) on the LSP admin page.
 */
export const CreateApiClientSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Client name is required.")
    .max(120, "Client name must be 120 characters or fewer."),
  lspId: z.string().uuid("LSP is required."),
});
export type CreateApiClientValues = z.infer<typeof CreateApiClientSchema>;

/**
 * Validation schema for the edit-API-client dialog.
 */
export const EditApiClientSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(120, "Name must be 120 characters or fewer."),
  status: ApiClientStatus,
});
export type EditApiClientValues = z.infer<typeof EditApiClientSchema>;
