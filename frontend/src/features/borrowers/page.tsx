/**
 * Borrowers directory list (`/borrowers`).
 *
 * Renders the URL-bound search bar over a server-paged table of every
 * borrower visible to internal sessions. Filter state is owned by
 * `useUrlFilters(BorrowerListFilters)` so deep-links round-trip; the
 * TanStack-Query cache key is the filter snapshot so back/forward hits
 * the cache instead of re-firing the request.
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
import { BorrowersFilterBar } from "./components/BorrowersFilterBar";
import { BorrowersTable } from "./components/BorrowersTable";
import { useBorrowersList } from "./hooks/useBorrowersList";
import { BorrowerListFilters } from "./list-types";

export function BorrowersPage() {
  const [filters, setFilters] = useUrlFilters(BorrowerListFilters);
  const query = useBorrowersList(filters);

  return (
    <div className="flex flex-col gap-6 p-6" data-testid="borrowers-page" data-page="borrowers">
      <PageHeader
        title="Borrowers"
        description="Browse and search the borrower directory across every LSP."
      />

      <BorrowersFilterBar />

      {query.isError && isUnauthorizedApiError(query.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to borrowers"
          description="The borrower directory is restricted to internal users with the appropriate permissions."
        />
      ) : query.isError ? (
        <ErrorState
          title="Couldn't load borrowers"
          description="The directory couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void query.refetch();
            },
          }}
        />
      ) : (
        <BorrowersTable
          data={query.data}
          isLoading={query.isPending && query.data === undefined}
          filters={filters}
          onFiltersChange={setFilters}
        />
      )}
    </div>
  );
}

export default BorrowersPage;
export const Component = BorrowersPage;
