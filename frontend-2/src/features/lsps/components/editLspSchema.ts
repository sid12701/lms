/**
 * Edit-LSP form schema (sibling to LspEditDialog).
 *
 * `code` is immutable post-create — only `name` and `status` are editable.
 */
import { z } from "zod";
import { Lsp, LspStatus } from "@/schemas/lsp";

export const EditLspFormSchema = z.object({
  name: Lsp.shape.name,
  status: LspStatus,
});

export type EditLspFormValues = z.infer<typeof EditLspFormSchema>;
