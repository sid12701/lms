import { useQuery } from "@tanstack/react-query";
import { fetchMyLoanPayments, fetchMyLoanRepaymentSchedule } from "../api";

function myLoanScheduleQueryKey(loanAccountId: string) {
  return ["my-loans", "schedule", loanAccountId] as const;
}

function myLoanPaymentsQueryKey(loanAccountId: string) {
  return ["my-loans", "payments", loanAccountId] as const;
}

export function useMyLoanServicing(loanAccountId: string) {
  const scheduleQuery = useQuery({
    queryKey: myLoanScheduleQueryKey(loanAccountId),
    queryFn: ({ signal }) => fetchMyLoanRepaymentSchedule(loanAccountId, signal),
    staleTime: 15_000,
  });

  const paymentsQuery = useQuery({
    queryKey: myLoanPaymentsQueryKey(loanAccountId),
    queryFn: ({ signal }) => fetchMyLoanPayments(loanAccountId, signal),
    staleTime: 15_000,
  });

  const loading = scheduleQuery.isPending || paymentsQuery.isPending;
  const error = scheduleQuery.error ?? paymentsQuery.error;

  return {
    schedule: scheduleQuery.data ?? null,
    payments: paymentsQuery.data?.items ?? null,
    paymentsTotalCount: paymentsQuery.data?.totalCount ?? null,
    paymentsTruncated: paymentsQuery.data?.truncated ?? false,
    loading,
    error,
    refetch: () => Promise.all([scheduleQuery.refetch(), paymentsQuery.refetch()]),
  };
}
