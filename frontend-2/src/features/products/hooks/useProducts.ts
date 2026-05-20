/**
 * TanStack Query wrapper around `listProducts`.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listProducts } from "../api";
import type { ProductsListFilters, ProductsListResponse } from "../types";

export const PRODUCTS_LIST_QUERY_KEY = ["products", "list"] as const;

export function useProducts(
  filters: ProductsListFilters,
): UseQueryResult<ProductsListResponse, Error> {
  return useQuery({
    queryKey: [...PRODUCTS_LIST_QUERY_KEY, filters],
    queryFn: () => listProducts(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
