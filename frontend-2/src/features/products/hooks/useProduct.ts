/**
 * TanStack Query wrapper around `getProduct`.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { getProduct } from "../api";
import type { ProductDetailResponse } from "../types";

export const PRODUCT_DETAIL_QUERY_KEY = ["products", "detail"] as const;

export function useProduct(id: string | null): UseQueryResult<ProductDetailResponse, Error> {
  return useQuery({
    queryKey: [...PRODUCT_DETAIL_QUERY_KEY, id],
    queryFn: () => getProduct(id ?? ""),
    enabled: Boolean(id),
    staleTime: 30_000,
  });
}
