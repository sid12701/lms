/**
 * TanStack Query wrapper around `misPreview`.
 *
 * The preview table is server-paged; the page passes `page` / `pageSize` in
 * with the rest of the filter snapshot. Cache key keys on the full filter
 * snapshot so each pagination + filter combination is independently cached.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { misPreview } from "../api";
import type { MisPreviewFilters, MisPreviewResponseDto } from "../types";

export const MIS_PREVIEW_QUERY_KEY = ["reports", "mis-preview"] as const;

export function useMisPreview(
  filters: MisPreviewFilters,
): UseQueryResult<MisPreviewResponseDto, Error> {
  return useQuery({
    queryKey: [...MIS_PREVIEW_QUERY_KEY, filters],
    queryFn: () => misPreview(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
