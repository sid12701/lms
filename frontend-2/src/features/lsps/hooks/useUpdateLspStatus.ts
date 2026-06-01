/**
 * Mutation hook — updates LSP status via the live admin status API.
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { listLspAuditEvents, updateLspStatus } from "../api";
import type { LspMutationResponse, LspsListResponse, UpdateLspStatusInput } from "../types";
import { lspAuditEventsQueryKey } from "./useLspAuditEvents";
import { LSPS_LIST_QUERY_KEY } from "./useLsps";

export interface UpdateLspStatusVariables extends UpdateLspStatusInput {
  id: string;
}

export function useUpdateLspStatus(): UseMutationResult<
  LspMutationResponse,
  Error,
  UpdateLspStatusVariables
> {
  const queryClient = useQueryClient();
  return useMutation<LspMutationResponse, Error, UpdateLspStatusVariables>({
    mutationFn: ({ id, ...rest }) => updateLspStatus(id, rest),
    onSuccess: async (res, vars) => {
      queryClient.setQueriesData<LspsListResponse>({ queryKey: [...LSPS_LIST_QUERY_KEY] }, (cached) => {
        if (!cached) return cached;
        return {
          ...cached,
          items: cached.items.map((row) =>
            row.id === vars.id ? { ...row, ...res.lsp, status: res.lsp.status } : row,
          ),
        };
      });
      await queryClient.refetchQueries({ queryKey: [...LSPS_LIST_QUERY_KEY] });
      await queryClient.fetchQuery({
        queryKey: lspAuditEventsQueryKey(vars.id),
        queryFn: () => listLspAuditEvents(vars.id),
      });
      const verb =
        vars.status === "INACTIVE"
          ? "Disabled"
          : vars.status === "ACTIVE"
            ? "Reactivated"
            : "Updated";
      toast.success(`${verb} LSP ${res.lsp.code}`);
    },
  });
}
