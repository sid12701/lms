import { requestJson } from "@/lib/api/http-client";

export interface LspOption {
  id: string;
  code: string;
  name: string;
  status: string;
}

const LSP_OPTIONS_PATH = "/api/v1/internal/admin/lsp-options";

export async function listLspOptions(): Promise<LspOption[]> {
  const rows = await requestJson<LspOption[]>(LSP_OPTIONS_PATH);
  return rows.filter((row) => row.status === "ACTIVE");
}
