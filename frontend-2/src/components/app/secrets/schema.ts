import { z } from "zod";

/**
 * Validation schema for the rotate-secret confirmation dialog. The reason is
 * the only operator-supplied field and is recorded in the secret-rotation
 * audit row.
 */
export const RotateSecretSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, "Reason is required.")
    .max(500, "Reason must be 500 characters or fewer."),
});
export type RotateSecretValues = z.infer<typeof RotateSecretSchema>;
