/**
 * Create-LSP form schema (sibling to LspCreateDialog).
 *
 * Mirrors the wire-level `Lsp.code` + `Lsp.name` constraints so client-side
 * validation matches what the mock router will reject anyway.
 */
import { z } from "zod";
import { Lsp } from "@/schemas/lsp";

export const CreateLspFormSchema = z.object({
  code: Lsp.shape.code,
  name: Lsp.shape.name,
});

export type CreateLspFormValues = z.infer<typeof CreateLspFormSchema>;
