import { Suspense, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  type ColumnDef,
  type Table as ReactTable,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { ArrowLeft, Download, Loader2, Plus, ShieldCheck } from "lucide-react";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

import { PageEyebrow } from "@/components/app/layout/PageEyebrow";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { PageSection } from "@/components/app/layout/PageSection";
import { KpiStrip } from "@/components/app/layout/KpiStrip";
import { RightRail } from "@/components/app/layout/RightRail";

import { TabularNumber } from "@/components/app/misc/TabularNumber";
import { CopyableId } from "@/components/app/misc/CopyableId";
import { KbdHint } from "@/components/app/misc/KbdHint";
import { AvatarInitials } from "@/components/app/misc/AvatarInitials";

import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { PermissionDeniedState } from "@/components/app/feedback/PermissionDeniedState";
import { ContentState } from "@/components/app/feedback/ContentState";
import { TableSkeleton } from "@/components/app/feedback/Skeletons/TableSkeleton";
import { KpiSkeleton } from "@/components/app/feedback/Skeletons/KpiSkeleton";
import { CardSkeleton } from "@/components/app/feedback/Skeletons/CardSkeleton";
import { FormSkeleton } from "@/components/app/feedback/Skeletons/FormSkeleton";

import { StatusBadge } from "@/components/app/status/StatusBadge";
import { AccountStatusBadge } from "@/components/app/status/AccountStatusBadge";
import { DpdBadge, type DpdBucketKey } from "@/components/app/status/DpdBadge";

import { DataTable } from "@/components/app/data/DataTable";
import { DataTableColumnHeader } from "@/components/app/data/DataTableColumnHeader";
import { DataTableViewOptions } from "@/components/app/data/DataTableViewOptions";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { FilterBar } from "@/components/app/data/FilterBar";
import { DensityToggle } from "@/components/app/data/DensityToggle";

import { STATUS_META } from "@/lib/lifecycle";
import type { LoanAccountStatus, LoanStatus } from "@/types";
import { cn } from "@/lib/utils";

interface SandboxLoanRow {
  id: string;
  application: string;
  borrower: string;
  status: LoanStatus;
  amount: number;
  dpd: DpdBucketKey;
}

const SAMPLE_ROWS: SandboxLoanRow[] = [
  {
    id: "ln-001",
    application: "BC-AP-024501",
    borrower: "Aanya Mehra",
    status: "DISBURSED",
    amount: 250000,
    dpd: "B0",
  },
  {
    id: "ln-002",
    application: "BC-AP-024502",
    borrower: "Karthik Nair",
    status: "AWAITING_APPROVAL",
    amount: 100000,
    dpd: "B0",
  },
  {
    id: "ln-003",
    application: "BC-AP-024503",
    borrower: "Riya Sehgal",
    status: "UNDER_REPAYMENT",
    amount: 540000,
    dpd: "B31_60",
  },
  {
    id: "ln-004",
    application: "BC-AP-024504",
    borrower: "Vikram Iyer",
    status: "CLOSED",
    amount: 80000,
    dpd: "B0",
  },
  {
    id: "ln-005",
    application: "BC-AP-024505",
    borrower: "Mira Pandit",
    status: "REJECTED",
    amount: 175000,
    dpd: "B0",
  },
];

const ACCOUNT_STATUSES: LoanAccountStatus[] = [
  "PENDING_DISBURSEMENT",
  "DISBURSEMENT_REQUESTED",
  "DISBURSED",
  "INVALID",
  "CLOSED",
  "FORECLOSED",
  "DISBURSEMENT_FAILED",
  "DISBURSEMENT_PENDING_RECONCILIATION",
];

const DPD_BUCKETS: DpdBucketKey[] = ["B0", "B1_30", "B31_60", "B61_90", "B90_PLUS", "NPA"];

const SECTIONS = [
  { id: "layout", label: "Layout" },
  { id: "misc", label: "Misc" },
  { id: "status", label: "Status badges" },
  { id: "feedback", label: "Feedback" },
  { id: "data", label: "Data table" },
  { id: "filters", label: "Filters & density" },
] as const;

const FILTER_SCHEMA = z.object({
  q: z.string().optional(),
  status: z.enum(["DISBURSED", "REJECTED", "AWAITING_APPROVAL"]).optional(),
});

function ColorSwatch({ name, token }: { name: string; token: string }) {
  return (
    <div className="border-border bg-surface rounded-container flex items-center gap-3 border p-3">
      <span
        aria-hidden="true"
        className={cn("rounded-container size-8 shrink-0 border", `bg-${token}`)}
        style={{ backgroundColor: `var(--color-${token.replace("color-", "")})` }}
      />
      <div className="min-w-0 text-xs">
        <div className="text-foreground font-medium">{name}</div>
        <div className="text-foreground-muted truncate font-mono">{token}</div>
      </div>
    </div>
  );
}

function SandboxMain({
  columns,
  loanStatuses,
}: {
  columns: ColumnDef<SandboxLoanRow>[];
  loanStatuses: LoanStatus[];
}) {
  return (
    <main className="flex min-w-0 flex-col gap-8">
      <PageHeader
        eyebrow="Dev"
        title="Components sandbox"
        description="Visual QA surface for every composed component. DEV-only route."
        actions={
          <>
            <Button type="button" variant="outline" size="sm">
              <Download aria-hidden="true" /> Export
            </Button>
            <Button type="button" size="sm">
              <Plus aria-hidden="true" /> New
            </Button>
          </>
        }
      />

      <PageSection id="layout" eyebrow="Layout" title="PageSection, KpiStrip, palette swatches">
        <KpiStrip>
          <div className="border-border bg-surface rounded-container border p-4">
            <div className="text-foreground-muted text-xs uppercase">Open loans</div>
            <div className="text-foreground mt-1 text-2xl font-semibold">
              <TabularNumber value={1248} />
            </div>
          </div>
          <div className="border-border bg-surface rounded-container border p-4">
            <div className="text-foreground-muted text-xs uppercase">Disbursed (mtd)</div>
            <div className="text-foreground mt-1 text-2xl font-semibold">
              <TabularNumber value={4520000} variant="currency" />
            </div>
          </div>
          <div className="border-border bg-surface rounded-container border p-4">
            <div className="text-foreground-muted text-xs uppercase">Avg. DPD</div>
            <div className="text-foreground mt-1 text-2xl font-semibold">
              <TabularNumber value={3.42} variant="percent" />
            </div>
          </div>
          <div className="border-border bg-surface rounded-container border p-4">
            <div className="text-foreground-muted text-xs uppercase">Documents</div>
            <div className="text-foreground mt-1 text-2xl font-semibold">
              <TabularNumber value={97.5} variant="percent" />
            </div>
          </div>
        </KpiStrip>

        <div className="mt-5 grid grid-cols-2 gap-3 md:grid-cols-4">
          <ColorSwatch name="Primary" token="color-primary" />
          <ColorSwatch name="Accent" token="color-accent" />
          <ColorSwatch name="Success" token="color-success" />
          <ColorSwatch name="Warning" token="color-warning" />
          <ColorSwatch name="Danger" token="color-danger" />
          <ColorSwatch name="Info" token="color-info" />
          <ColorSwatch name="Revoked" token="color-revoked" />
          <ColorSwatch name="Neutral" token="color-neutral" />
        </div>
      </PageSection>

      <PageSection id="misc" eyebrow="Misc" title="Tabular, copyable IDs, kbd, avatars">
        <div className="flex flex-wrap items-center gap-4">
          <TabularNumber value={1234567} variant="currency" decimals={0} />
          <TabularNumber value={12.345} variant="percent" />
          <CopyableId value="bc-app-9c3a-2d11-4e10-b8f7" truncate={18} label="Application ID" />
          <KbdHint keys={["Cmd", "K"]} />
          <KbdHint keys={["Ctrl", "Shift", "P"]} />
          <AvatarInitials name="Aanya Mehra" size="sm" />
          <AvatarInitials name="Karthik Nair" tone="accent" />
          <AvatarInitials name="Riya Sehgal" size="lg" tone="neutral" />
        </div>
      </PageSection>

      <PageSection id="status" eyebrow="Status" title="StatusBadge, AccountStatusBadge, DpdBadge">
        <h3 className="text-foreground mb-2 text-sm font-semibold">
          Loan status — subtle (default)
        </h3>
        <div className="mb-5 flex flex-wrap gap-2">
          {loanStatuses.map((status) => (
            <StatusBadge key={status} status={status} />
          ))}
        </div>
        <h3 className="text-foreground mb-2 text-sm font-semibold">Loan status — solid</h3>
        <div className="mb-5 flex flex-wrap gap-2">
          {loanStatuses.slice(0, 8).map((status) => (
            <StatusBadge key={status} status={status} variant="default" />
          ))}
        </div>
        <h3 className="text-foreground mb-2 text-sm font-semibold">Account status</h3>
        <div className="mb-5 flex flex-wrap gap-2">
          {ACCOUNT_STATUSES.map((status) => (
            <AccountStatusBadge key={status} status={status} />
          ))}
        </div>
        <h3 className="text-foreground mb-2 text-sm font-semibold">DPD buckets</h3>
        <div className="flex flex-wrap gap-2">
          {DPD_BUCKETS.map((bucket) => (
            <DpdBadge key={bucket} bucket={bucket} />
          ))}
          <DpdBadge bucket="B31_60" days={47} />
        </div>
      </PageSection>

      <PageSection id="feedback" eyebrow="Feedback" title="Empty / Error / Permission / Skeletons">
        <div className="grid gap-4 md:grid-cols-2">
          <div className="border-border rounded-container border p-3">
            <EmptyState
              title="No loan applications"
              description="No loans match the current filters."
            />
          </div>
          <div className="border-border rounded-container border p-3">
            <ErrorState
              title="Something went wrong"
              description="The backend returned an error."
              retry={{ label: "Retry", onClick: () => undefined }}
            />
          </div>
          <div className="border-border rounded-container border p-3">
            <PermissionDeniedState
              title="No access"
              description="Your role cannot view this surface."
            />
          </div>
          <div className="border-border rounded-container border p-3">
            <ContentState status="loading">
              <span>Body content (hidden while loading)</span>
            </ContentState>
          </div>
          <div className="border-border rounded-container border p-3">
            <KpiSkeleton />
          </div>
          <div className="border-border rounded-container border p-3">
            <CardSkeleton />
          </div>
          <div className="border-border rounded-container border p-3">
            <FormSkeleton />
          </div>
          <div className="border-border rounded-container border p-3">
            <TableSkeleton />
          </div>
        </div>
      </PageSection>

      <PageSection
        id="data"
        eyebrow="Data"
        title="DataTable + column header + view options + pagination"
        actions={<DensityToggle />}
      >
        <SandboxTable rows={SAMPLE_ROWS} columns={columns} />
      </PageSection>

      <PageSection id="filters" eyebrow="Filters" title="FilterBar + DensityToggle">
        <FilterBar schema={FILTER_SCHEMA} label="Filters">
          {({ filters, setFilters }) => (
            <>
              <Badge variant="secondary">{`Active: ${Object.keys(filters).length}`}</Badge>
              <Button
                type="button"
                size="sm"
                variant={filters.status === "DISBURSED" ? "default" : "outline"}
                onClick={() =>
                  setFilters({
                    status: filters.status === "DISBURSED" ? undefined : "DISBURSED",
                  })
                }
              >
                Disbursed
              </Button>
              <Button
                type="button"
                size="sm"
                variant={filters.status === "AWAITING_APPROVAL" ? "default" : "outline"}
                onClick={() =>
                  setFilters({
                    status:
                      filters.status === "AWAITING_APPROVAL" ? undefined : "AWAITING_APPROVAL",
                  })
                }
              >
                Awaiting approval
              </Button>
            </>
          )}
        </FilterBar>
      </PageSection>
    </main>
  );
}

// Item 5 fix — lazy-route race on first paint: every useState below initialises
// synchronously so the page commits with a stable shape on first paint, and the
// body is wrapped in a defensive <Suspense> boundary so any future lazy child
// renders against a stable fallback instead of flickering the parent.
export function ComponentsSandboxPage() {
  const [active, setActive] = useState<(typeof SECTIONS)[number]["id"]>("layout");

  const columns = useMemo<ColumnDef<SandboxLoanRow>[]>(
    () => [
      {
        accessorKey: "application",
        meta: { label: "Application" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Application" />,
        cell: ({ row }) => <CopyableId value={row.original.application} truncate={14} />,
      },
      {
        accessorKey: "borrower",
        meta: { label: "Borrower" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Borrower" />,
        cell: ({ row }) => (
          <span className="inline-flex items-center gap-2">
            <AvatarInitials name={row.original.borrower} size="sm" tone="brand" />
            <span>{row.original.borrower}</span>
          </span>
        ),
      },
      {
        accessorKey: "status",
        meta: { label: "Status" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
      },
      {
        accessorKey: "dpd",
        meta: { label: "DPD" },
        header: ({ column }) => <DataTableColumnHeader column={column} title="DPD" />,
        cell: ({ row }) => <DpdBadge bucket={row.original.dpd} />,
      },
      {
        accessorKey: "amount",
        meta: { label: "Amount", numeric: true },
        header: ({ column }) => <DataTableColumnHeader column={column} title="Amount" numeric />,
        cell: ({ row }) => (
          <TabularNumber value={row.original.amount} variant="currency" decimals={0} />
        ),
      },
    ],
    [],
  );

  const loanStatuses = Object.keys(STATUS_META) as LoanStatus[];

  return (
    <div className="bg-background min-h-screen">
      <div className="mx-auto max-w-[1320px] px-6 pt-6">
        {/* Item 9 — back-link to home (uses react-router Link, not raw anchor). */}
        <Link
          to="/home"
          data-slot="sandbox-back-link"
          className="text-foreground-muted hover:text-foreground inline-flex items-center gap-1.5 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current"
        >
          <ArrowLeft aria-hidden="true" className="h-3.5 w-3.5" />
          <span>Back to home</span>
        </Link>
      </div>
      <Suspense
        fallback={
          <div
            role="status"
            aria-live="polite"
            className="flex min-h-[40vh] items-center justify-center"
          >
            <Loader2 aria-hidden="true" className="text-foreground-muted h-5 w-5 animate-spin" />
            <span className="sr-only">Loading sandbox</span>
          </div>
        }
      >
        <div className="mx-auto grid max-w-[1320px] grid-cols-1 gap-6 p-6 lg:grid-cols-[200px_1fr_288px]">
          {/* Sticky TOC */}
          <nav aria-label="Sandbox sections" className="sticky top-6 hidden h-fit lg:block">
            <PageEyebrow className="mb-2 px-2">On this page</PageEyebrow>
            <ul className="flex flex-col gap-0.5">
              {SECTIONS.map((s) => (
                <li key={s.id}>
                  <a
                    href={`#${s.id}`}
                    onClick={() => setActive(s.id)}
                    data-state={active === s.id ? "active" : undefined}
                    className={cn(
                      "hover:bg-surface-muted text-foreground-muted block rounded px-3 py-1.5 text-sm",
                      active === s.id && "bg-surface-muted text-foreground font-medium",
                    )}
                  >
                    {s.label}
                  </a>
                </li>
              ))}
            </ul>
          </nav>

          <SandboxMain columns={columns} loanStatuses={loanStatuses} />

          {/* Right rail */}
          <RightRail>
            <div className="border-border bg-surface rounded-container flex flex-col gap-3 border p-4">
              <PageEyebrow>Sandbox</PageEyebrow>
              <p className="text-foreground text-sm">
                Visual QA only. Components mounted with hard-coded fixtures so changes are
                reviewable in one place.
              </p>
              <Badge variant="outline" className="gap-1">
                <ShieldCheck aria-hidden="true" /> DEV
              </Badge>
            </div>
          </RightRail>
        </div>
      </Suspense>
    </div>
  );
}

function SandboxTable({
  rows,
  columns,
}: {
  rows: SandboxLoanRow[];
  columns: ColumnDef<SandboxLoanRow>[];
}) {
  // Wrap DataTable + its toolbar in a small inner component to scope the
  // shared TanStack instance via state — kept inline (not factored out)
  // because the sandbox is a dev-only review surface.
  return (
    <DataTableScaffold rows={rows} columns={columns}>
      {(table) => (
        <div className="mb-2 flex items-center justify-end gap-2">
          <DataTableViewOptions table={table} />
        </div>
      )}
    </DataTableScaffold>
  );
}

function DataTableScaffold<TData>({
  rows,
  columns,
  children,
}: {
  rows: TData[];
  columns: ColumnDef<TData>[];
  children: (table: ReactTable<TData>) => React.ReactNode;
}) {
  // Owns a live TanStack table instance so the toolbar (view options) and
  // pagination row can both read/write the same pagination + visibility state.
  const table = useReactTable<TData>({
    data: rows,
    columns,
    initialState: { pagination: { pageIndex: 0, pageSize: 10 } },
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  });

  return (
    <div className="flex flex-col gap-3">
      {children(table)}
      <DataTable
        columns={columns}
        data={rows}
        density="comfortable"
        ariaLabel="Sample loan applications"
      />
      <DataTablePagination table={table} />
    </div>
  );
}

export default ComponentsSandboxPage;
export const Component = ComponentsSandboxPage;
