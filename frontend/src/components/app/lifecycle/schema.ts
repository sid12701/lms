import { z } from "zod";

/**
 * Reason field for lifecycle transitions.
 *
 * The component layer (TransitionConfirmDialog) decides — based on
 * `LifecycleAction.requiresReason` — whether to attach the `min(1)`
 * required schema or the optional one. Keeping the two schemas here
 * (rather than co-located with the dialog component) avoids the
 * fast-refresh "non-component export" warning.
 */
export const LifecycleReasonRequiredSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, "Reason is required.")
    .max(1000, "Reason must be 1000 characters or fewer."),
});
export type LifecycleReasonRequiredValues = z.infer<typeof LifecycleReasonRequiredSchema>;

export const LifecycleReasonOptionalSchema = z.object({
  reason: z
    .string()
    .trim()
    .max(1000, "Reason must be 1000 characters or fewer.")
    .optional()
    .default(""),
});
export type LifecycleReasonOptionalValues = z.infer<typeof LifecycleReasonOptionalSchema>;
