/**
 * Mutation hook — creates a new LSP via the mock admin router.
 *
 * Invalidates every cached `lsps` query on success + surfaces a sonner toast.
 * The caller mints the BR-5 idempotency key (the dialog does this at submit).
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { createLsp } from "../api";
import type { CreateLspInput, LspMutationResponse } from "../types";
import { LSPS_LIST_QUERY_KEY } from "./useLsps";

export function useCreateLsp(): UseMutationResult<LspMutationResponse, Error, CreateLspInput> {
  const queryClient = useQueryClient();
  return useMutation<LspMutationResponse, Error, CreateLspInput>({
    mutationFn: (input) => createLsp(input),
    onSuccess: (res) => {
      void queryClient.invalidateQueries({ queryKey: [...LSPS_LIST_QUERY_KEY] });
      toast.success(`Created LSP ${res.lsp.code}`);
    },
  });
}
