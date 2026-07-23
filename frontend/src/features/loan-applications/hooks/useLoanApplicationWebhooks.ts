/**
 * TanStack Query wrapper around `fetchLoanApplicationWebhooks`.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLoanApplicationWebhooks } from "../api-detail";
import { LOAN_APPLICATION_DETAIL_QUERY_KEY } from "./useLoanApplicationDetail";
import type { LoanApplicationWebhooksResponse } from "../types";

function loanApplicationWebhooksQueryKey(id: string) {
  return [LOAN_APPLICATION_DETAIL_QUERY_KEY, id, "webhooks"] as const;
}

export interface UseLoanApplicationWebhooksOptions {
  /** When false, the query is paused — used to gate by active tab. */
  enabled?: boolean;
}

export function useLoanApplicationWebhooks(
  id: string,
  options: UseLoanApplicationWebhooksOptions = {},
): UseQueryResult<LoanApplicationWebhooksResponse, Error> {
  return useQuery({
    queryKey: loanApplicationWebhooksQueryKey(id),
    queryFn: () => fetchLoanApplicationWebhooks(id),
    staleTime: 30_000,
    enabled: typeof id === "string" && id.length > 0 && (options.enabled ?? true),
  });
}
