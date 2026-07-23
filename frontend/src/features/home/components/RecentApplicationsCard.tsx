import { forwardRef, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import type { ColumnDef } from "@tanstack/react-table";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable } from "@/components/app/data/DataTable";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { formatINR, formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";
import { Files } from "lucide-react";
import type { HomeRecentApplication } from "../types";

interface RecentApplicationsCardProps {
  applications: readonly HomeRecentApplication[];
  className?: string;
}

/**
 * Recent applications strip on the home dashboard. Rows are clickable
 * (and keyboard-focusable) — Enter / Space / mouse navigates to the
 * detail surface. Borrower names are already masked by the API layer
 * (PII reveal lives on the detail page).
 */
export const RecentApplicationsCard = forwardRef<HTMLDivElement, RecentApplicationsCardProps>(
  function RecentApplicationsCard({ applications, className }, ref) {
    const navigate = useNavigate();

    const columns = useMemo<ColumnDef<HomeRecentApplication>[]>(
      () => [
        {
          id: "id",
          header: "Application",
          accessorKey: "id",
          // Rendered as plain mono text rather than CopyableId — the table row
          // itself is the navigation affordance (role="button"), and nesting a
          // copy button inside it would violate WCAG nested-interactive.
          cell: ({ row }) => {
            const id = row.original.id;
            const short = id.length > 8 ? `${id.slice(0, 8)}…` : id;
            return (
              <span className="text-foreground font-mono text-xs" title={id}>
                {short}
              </span>
            );
          },
          meta: { label: "Application" },
        },
        {
          id: "borrower",
          header: "Borrower",
          accessorKey: "borrowerNameMasked",
          cell: ({ row }) => (
            <span className="text-foreground text-sm">{row.original.borrowerNameMasked}</span>
          ),
          meta: { label: "Borrower" },
        },
        {
          id: "product",
          header: "Product",
          accessorKey: "productName",
          meta: { label: "Product" },
        },
        {
          id: "lsp",
          header: "LSP",
          accessorKey: "lspName",
          meta: { label: "LSP" },
        },
        {
          id: "status",
          header: "Status",
          accessorKey: "status",
          cell: ({ row }) => <StatusBadge status={row.original.status} />,
          meta: { label: "Status" },
        },
        {
          id: "requestedAmount",
          header: "Requested",
          accessorKey: "requestedAmount",
          cell: ({ row }) => formatINR(row.original.requestedAmount),
          meta: { numeric: true, label: "Requested" },
        },
        {
          id: "createdAt",
          header: "Created",
          accessorKey: "createdAt",
          cell: ({ row }) => (
            <span className="text-foreground-muted text-sm">
              {formatRelative(row.original.createdAt)}
            </span>
          ),
          meta: { label: "Created" },
        },
      ],
      [],
    );

    const data = useMemo(() => [...applications], [applications]);

    return (
      <Card ref={ref} data-slot="recent-applications" className={cn(className)}>
        <CardHeader>
          <CardTitle>Recent applications</CardTitle>
        </CardHeader>
        <CardContent>
          {data.length === 0 ? (
            <EmptyState
              icon={Files}
              title="No recent applications"
              description="New applications across your visibility window will show up here."
            />
          ) : (
            <DataTable
              columns={columns}
              data={data}
              rowIdKey="id"
              density="comfortable"
              ariaLabel="Recent applications"
              getRowAction={(row) => navigate(`/loan-applications/${row.original.id}`)}
            />
          )}
        </CardContent>
      </Card>
    );
  },
);
