/**
 * Mutation — create a loan product.
 *
 * Idempotency key is minted by the caller (the dialog) at submit time, not
 * here. On success, invalidates the products list cache + fires a toast.
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { createProduct } from "../api";
import type { CreateProductInput, ProductMutationResponse } from "../types";
import { PRODUCTS_LIST_QUERY_KEY } from "./useProducts";

export function useCreateProduct(): UseMutationResult<
  ProductMutationResponse,
  Error,
  CreateProductInput
> {
  const queryClient = useQueryClient();
  return useMutation<ProductMutationResponse, Error, CreateProductInput>({
    mutationFn: (input) => createProduct(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...PRODUCTS_LIST_QUERY_KEY] });
      toast.success("Product created");
    },
  });
}
