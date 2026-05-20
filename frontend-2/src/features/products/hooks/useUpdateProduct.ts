/**
 * Mutation — update a loan product (any subset of mutable fields).
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { updateProduct } from "../api";
import type { ProductMutationResponse, UpdateProductInput } from "../types";
import { PRODUCTS_LIST_QUERY_KEY } from "./useProducts";
import { PRODUCT_DETAIL_QUERY_KEY } from "./useProduct";

export interface UpdateProductVariables extends UpdateProductInput {
  id: string;
}

export function useUpdateProduct(): UseMutationResult<
  ProductMutationResponse,
  Error,
  UpdateProductVariables
> {
  const queryClient = useQueryClient();
  return useMutation<ProductMutationResponse, Error, UpdateProductVariables>({
    mutationFn: ({ id, ...rest }) => updateProduct(id, rest),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...PRODUCTS_LIST_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [...PRODUCT_DETAIL_QUERY_KEY] });
      toast.success("Product updated");
    },
  });
}
