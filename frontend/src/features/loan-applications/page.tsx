/**
 * Phase 5 loan-applications triage list.
 *
 * Composes the URL-bound filter bar with the server-paged triage table.
 * Filter state lives in the URL via `useUrlFilters(LoanApplicationListFilters)`
 * so deep-links round-trip cleanly; both the FilterBar and the Table read
 * from the same hook and feed updates back through it. The TanStack Query
 * cache key is the filter snapshot, so back/forward navigation hits the
 * cache rather than re-firing the request.
 *
 * Role enforcement lives in the router (`RequireRole` for SYSTEM_ADMIN/OPS_USER);
 * this page does not duplicate the gate.
 */
import { ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { isUnauthorizedApiError } from "@/lib/api/api-errors";
import { useUrlFilters } from "@/lib/url-state";
import { listProducts } from "@/features/products/api";
import { listLspOptions } from "@/features/lsps/options";
import { LoanApplicationsFilterBar, LoanApplicationsTable } from "./components";
import { useLoanApplications } from "./hooks/useLoanApplications";
import { LoanApplicationListFilters } from "./types";
import { useEffect, useState } from "react";

export function LoanApplicationsPage() {
  const [filters, setFilters] = useUrlFilters(LoanApplicationListFilters);
  const query = useLoanApplications(filters);
  const [lspOptions, setLspOptions] = useState<readonly { value: string; label: string }[]>([]);
  const [productOptions, setProductOptions] = useState<readonly { value: string; label: string }[]>(
    [],
  );

  useEffect(() => {
    let cancelled = false;
    void listLspOptions()
      .then((rows) => {
        if (cancelled) return;
        setLspOptions(
          rows.map((row) => ({
            value: row.id,
            label: `${row.name} (${row.code})`,
          })),
        );
      })
      .catch(() => {
        if (!cancelled) setLspOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    void listProducts({ page: 0, pageSize: 500 })
      .then((response) => {
        if (cancelled) return;
        setProductOptions(
          response.items.map((product) => ({
            value: product.id,
            label: `${product.name} (${product.code})`,
          })),
        );
      })
      .catch(() => {
        if (!cancelled) setProductOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div
      className="flex flex-col gap-6 p-6"
      data-testid="loan-applications-page"
      data-page="loan-applications"
    >
      <PageHeader
        eyebrow="Workspace"
        title="Loan applications"
        description="Browse, filter, and act on every loan application across LSPs."
      />

      <LoanApplicationsFilterBar lspOptions={lspOptions} productOptions={productOptions} />

      {query.isError && isUnauthorizedApiError(query.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to loan applications"
          description="The triage list is restricted to internal users with the appropriate permissions."
        />
      ) : query.isError ? (
        <ErrorState
          title="Couldn't load applications"
          description="The triage list couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void query.refetch();
            },
          }}
        />
      ) : (
        <LoanApplicationsTable
          data={query.data}
          isLoading={query.isPending && query.data === undefined}
          filters={filters}
          onFiltersChange={setFilters}
        />
      )}
    </div>
  );
}

export default LoanApplicationsPage;
export const Component = LoanApplicationsPage;
