import { z } from "zod";
import { LspOperationalStatus, LspStatusChangeReason } from "@/schemas/lsp";

export const LspStatusChangeFormSchema = z.object({
  status: LspOperationalStatus,
  reason: LspStatusChangeReason,
  note: z.string().trim().min(1, "A note is required for audit.").max(2000),
});

export type LspStatusChangeFormValues = z.infer<typeof LspStatusChangeFormSchema>;
