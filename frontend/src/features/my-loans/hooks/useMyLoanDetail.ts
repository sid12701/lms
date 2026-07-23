import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchMyLoanDetail, type MyLoanDetail } from "../api";

export function myLoanDetailQueryKey(id: string) {
  return ["my-loans", "detail", id] as const;
}

export function useMyLoanDetail(id: string): UseQueryResult<MyLoanDetail, Error> {
  return useQuery({
    queryKey: myLoanDetailQueryKey(id),
    queryFn: () => fetchMyLoanDetail(id),
    enabled: id.length > 0,
    staleTime: 30_000,
  });
}
