import { z } from "zod";

/**
 * Zod schema for the ForeclosureRequestDialog form.
 *
 * - `reason` is required and audit-logged (BR-7-style).
 * - `note` is optional context. Empty input collapses to `undefined` so the
 *   dialog can normalise it to `null` on submit.
 */
export const ForeclosureRequestSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, "Reason is required.")
    .max(500, "Reason must be 500 characters or fewer."),
  note: z.string().trim().max(500, "Note must be 500 characters or fewer.").optional(),
});

export type ForeclosureRequestValues = z.infer<typeof ForeclosureRequestSchema>;
