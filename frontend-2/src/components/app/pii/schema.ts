import { z } from "zod";

export const PiiRevealReasonSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, "Reason is required.")
    .max(500, "Reason must be 500 characters or fewer."),
});
export type PiiRevealReasonValues = z.infer<typeof PiiRevealReasonSchema>;
