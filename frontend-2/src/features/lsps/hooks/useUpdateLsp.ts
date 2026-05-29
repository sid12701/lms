/**
 * Mutation hook — patches an LSP via the mock admin router.
 *
 * Invalidates every cached `lsps` query on success + surfaces a sonner toast.
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { updateLsp } from "../api";
import type { LspMutationResponse, UpdateLspInput } from "../types";
import { LSPS_LIST_QUERY_KEY } from "./useLsps";

export interface UpdateLspVariables extends UpdateLspInput {
  id: string;
}

export function useUpdateLsp(): UseMutationResult<LspMutationResponse, Error, UpdateLspVariables> {
  const queryClient = useQueryClient();
  return useMutation<LspMutationResponse, Error, UpdateLspVariables>({
    mutationFn: ({ id, ...rest }) => updateLsp(id, rest),
    onSuccess: (res) => {
      void queryClient.invalidateQueries({ queryKey: [...LSPS_LIST_QUERY_KEY] });
      toast.success(`Updated LSP ${res.lsp.code}`);
    },
  });
}
