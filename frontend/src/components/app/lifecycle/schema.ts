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
const LifecycleReasonRequiredSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, "Reason is required.")
    .max(1000, "Reason must be 1000 characters or fewer."),
});
const LifecycleReasonOptionalSchema = z.object({
  reason: z
    .string()
    .trim()
    .max(1000, "Reason must be 1000 characters or fewer.")
    .optional()
    .default(""),
});
/**
 * Full dialog schema: free-text reason plus (when the backend demands one —
 * REJECTED / DISBURSEMENT_RETRY targets) a structured reason code.
 */
export function lifecycleDialogSchema(opts: {
  requiresReason: boolean;
  requiresReasonCode: boolean;
}) {
  const base = opts.requiresReason ? LifecycleReasonRequiredSchema : LifecycleReasonOptionalSchema;
  return base.extend({
    reasonCode: opts.requiresReasonCode
      ? z.string().min(1, "Select a reason.")
      : z.string().optional().default(""),
  });
}

export interface LifecycleDialogValues {
  reason?: string;
  reasonCode?: string;
}
