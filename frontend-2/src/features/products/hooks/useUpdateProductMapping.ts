/**
 * Mutation — replace a product's LSP mapping wholesale.
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { updateProductMapping } from "../api";
import type {
  ProductMutationResponse,
  UpdateProductMappingInput,
} from "../types";
import { PRODUCTS_LIST_QUERY_KEY } from "./useProducts";
import { PRODUCT_DETAIL_QUERY_KEY } from "./useProduct";

export interface UpdateProductMappingVariables
  extends UpdateProductMappingInput {
  id: string;
}

export function useUpdateProductMapping(): UseMutationResult<
  ProductMutationResponse,
  Error,
  UpdateProductMappingVariables
> {
  const queryClient = useQueryClient();
  return useMutation<
    ProductMutationResponse,
    Error,
    UpdateProductMappingVariables
  >({
    mutationFn: ({ id, ...rest }) => updateProductMapping(id, rest),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...PRODUCTS_LIST_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [...PRODUCT_DETAIL_QUERY_KEY] });
      toast.success("Mapping updated");
    },
  });
}
