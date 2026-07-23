import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchInvalidReasons, type InvalidReasonOption } from "../api";

export const INVALID_REASONS_QUERY_KEY = ["my-loans", "invalid-reasons"] as const;

export function useInvalidReasons(enabled: boolean): UseQueryResult<InvalidReasonOption[], Error> {
  return useQuery({
    queryKey: INVALID_REASONS_QUERY_KEY,
    queryFn: fetchInvalidReasons,
    enabled,
    staleTime: Number.POSITIVE_INFINITY,
  });
}
