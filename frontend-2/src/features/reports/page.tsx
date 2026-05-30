/**
 * Phase 8 — `/reports` portfolio MIS surface (SYSTEM_ADMIN-only).
 *
 * Composes the filter bar, KPI summary cards, MIS preview table, the
 * "Generate report" dialog, and the user's request queue. Role enforcement
 * happens in the route layer (`RequireRole` for SYSTEM_ADMIN) and is also
 * defended by the mock handler (returns FORBIDDEN for any other role).
 *
 * Filter snapshot is page-local React state — reports is an internal-ops
 * surface, not a deep-link target, so the URL is intentionally not bound.
 */
import { useMemo, useState } from "react";
import { FileBarChart2, ShieldAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { ReportsFilterBar } from "./components/ReportsFilterBar";
import { MisSummaryCards } from "./components/MisSummaryCards";
import { MisPreviewTable } from "./components/MisPreviewTable";
import { ReportRequestsTable } from "./components/ReportRequestsTable";
import { CreateReportDialog, type CreateReportConfirmArgs } from "./components/CreateReportDialog";
import { useMisSummary } from "./hooks/useMisSummary";
import { useMisPreview } from "./hooks/useMisPreview";
import { useReportRequests } from "./hooks/useReportRequests";
import { useCreateReportRequest } from "./hooks/useCreateReportRequest";
import { useDownloadReportRequest } from "./hooks/useDownloadReportRequest";
import type { MisFilters, MisPreviewFilters, ReportRequest, ReportsPageFilters } from "./types";

const INITIAL_FILTERS: ReportsPageFilters = {
  lspId: null,
  dateFrom: null,
  dateTo: null,
};

const INITIAL_PAGE_SIZE = 25;

function isUnauthorized(err: unknown): boolean {
  if (!err) return false;
  if (typeof err === "object" && err !== null && "code" in err) {
    const code = (err as { code?: unknown }).code;
    if (code === "UNAUTHORIZED" || code === "FORBIDDEN") return true;
  }
  const msg = err instanceof Error ? err.message : String(err);
  return /UNAUTHORIZED|FORBIDDEN/i.test(msg);
}

function extractErrorMessage(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof Error && err.message) return err.message;
  return "Couldn't queue the report. Try again in a moment.";
}

export function ReportsPage() {
  const [filters, setFilters] = useState<ReportsPageFilters>(INITIAL_FILTERS);
  const [pagination, setPagination] = useState<{ page: number; pageSize: number }>({
    page: 0,
    pageSize: INITIAL_PAGE_SIZE,
  });
  const [dialogOpen, setDialogOpen] = useState(false);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);

  const misFilters: MisFilters = useMemo(
    () => ({
      lspId: filters.lspId ?? undefined,
      dateFrom: filters.dateFrom ?? undefined,
      dateTo: filters.dateTo ?? undefined,
    }),
    [filters.lspId, filters.dateFrom, filters.dateTo],
  );

  const previewFilters: MisPreviewFilters = useMemo(
    () => ({
      ...misFilters,
      page: pagination.page,
      pageSize: pagination.pageSize,
    }),
    [misFilters, pagination.page, pagination.pageSize],
  );

  const summaryQuery = useMisSummary(misFilters);
  const previewQuery = useMisPreview(previewFilters);
  const requestsQuery = useReportRequests();
  const createMutation = useCreateReportRequest();
  const downloadMutation = useDownloadReportRequest();

  const handleFiltersChange = (next: ReportsPageFilters) => {
    setFilters(next);
    // Filter change resets pagination so we don't land on a now-out-of-bounds page.
    setPagination((prev) => ({ page: 0, pageSize: prev.pageSize }));
  };

  const handlePreviewFiltersChange = (next: MisPreviewFilters) => {
    setPagination({
      page: next.page ?? 0,
      pageSize: next.pageSize ?? INITIAL_PAGE_SIZE,
    });
  };

  const handleDialogOpenChange = (open: boolean) => {
    if (!open) {
      // Don't allow closing while the mutation is in-flight.
      if (createMutation.isPending) return;
      setDialogOpen(false);
      createMutation.reset();
      return;
    }
    setDialogOpen(true);
  };

  const handleConfirmCreate = async ({ input, idempotencyKey }: CreateReportConfirmArgs) => {
    try {
      await createMutation.mutateAsync({ input, idempotencyKey });
      setDialogOpen(false);
      createMutation.reset();
    } catch {
      // Error surfaces via `createMutation.error` → dialog `errorMessage` prop.
      // Keep dialog open so the user can retry / cancel.
    }
  };

  const handleDownload = async (row: ReportRequest) => {
    setDownloadingId(row.id);
    try {
      const result = await downloadMutation.mutateAsync({ id: row.id });
      if (typeof window !== "undefined") {
        window.open(result.url, "_blank", "noopener,noreferrer");
      }
    } catch {
      // Error toast fires from the hook's onError.
    } finally {
      setDownloadingId(null);
    }
  };

  // Either the summary OR the preview being forbidden indicates an
  // access-level failure — render a friendly empty state instead of an
  // ErrorState so the screen never looks broken for an under-privileged user
  // who somehow escaped the route guard.
  const accessDenied =
    (summaryQuery.isError && isUnauthorized(summaryQuery.error)) ||
    (previewQuery.isError && isUnauthorized(previewQuery.error));

  const summaryHardError = summaryQuery.isError && !isUnauthorized(summaryQuery.error);
  const previewHardError = previewQuery.isError && !isUnauthorized(previewQuery.error);

  return (
    <div data-testid="reports-page" className="flex flex-col gap-6 p-6" data-density="comfortable">
      <PageHeader
        eyebrow="Reporting"
        title="Reports"
        description="Portfolio MIS preview and downloadable report generation."
        actions={
          <Button
            type="button"
            data-slot="reports-generate-button"
            onClick={() => setDialogOpen(true)}
            disabled={accessDenied}
          >
            <FileBarChart2 aria-hidden="true" className="size-4" />
            Generate report
          </Button>
        }
      />

      {accessDenied ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to reports"
          description="The portfolio MIS surface is restricted to system administrators."
        />
      ) : (
        <>
          <ReportsFilterBar filters={filters} onChange={handleFiltersChange} />

          {summaryHardError ? (
            <ErrorState
              title="Couldn't load portfolio KPIs"
              description="The summary KPIs couldn't be fetched. Try again in a moment."
              retry={{
                label: "Retry",
                onClick: () => {
                  void summaryQuery.refetch();
                },
              }}
            />
          ) : (
            <MisSummaryCards data={summaryQuery.data} isLoading={summaryQuery.isPending} />
          )}

          <section aria-labelledby="reports-requests-heading" className="flex flex-col gap-2">
            <h2 id="reports-requests-heading" className="text-foreground text-base font-semibold">
              Your report requests
            </h2>
            <ReportRequestsTable
              data={requestsQuery.data}
              isLoading={requestsQuery.isPending}
              isDownloading={downloadMutation.isPending}
              downloadingId={downloadingId}
              onDownload={(row) => {
                void handleDownload(row);
              }}
            />
          </section>

          <section aria-labelledby="reports-preview-heading" className="flex flex-col gap-2">
            <h2 id="reports-preview-heading" className="text-foreground text-base font-semibold">
              Portfolio preview
            </h2>
            {previewHardError ? (
              <ErrorState
                title="Couldn't load the portfolio preview"
                description="The MIS preview couldn't be fetched. Try again in a moment."
                retry={{
                  label: "Retry",
                  onClick: () => {
                    void previewQuery.refetch();
                  },
                }}
              />
            ) : (
              <MisPreviewTable
                data={previewQuery.data}
                isLoading={previewQuery.isPending}
                filters={previewFilters}
                onFiltersChange={handlePreviewFiltersChange}
              />
            )}
          </section>
        </>
      )}

      <CreateReportDialog
        open={dialogOpen}
        onOpenChange={handleDialogOpenChange}
        initialValues={filters}
        onConfirm={handleConfirmCreate}
        loading={createMutation.isPending}
        errorMessage={createMutation.isError ? extractErrorMessage(createMutation.error) : null}
      />
    </div>
  );
}

export default ReportsPage;
export const Component = ReportsPage;
