/**
 * Compact list of the requesting user's report requests with status badges
 * + a Download CTA that lights up only once status === COMPLETED.
 *
 * The backend auto-advances QUEUED → PROCESSING → COMPLETED on a 5s/10s
 * timer; the parent page polls every 5s so a freshly queued row visibly
 * progresses without manual refresh.
 */
import { CheckCircle2, CircleDashed, Download, Loader2, XCircle } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { ReportRequest, ReportStatus } from "../types";

const STATUS_META: Record<ReportStatus, { label: string; icon: LucideIcon; className: string }> = {
  QUEUED: {
    label: "Queued",
    icon: CircleDashed,
    className: "border-border bg-surface-muted text-foreground-muted",
  },
  PROCESSING: {
    label: "Processing",
    icon: Loader2,
    className: "border-info/40 bg-info/10 text-info",
  },
  COMPLETED: {
    label: "Completed",
    icon: CheckCircle2,
    className: "border-success/40 bg-success/10 text-success",
  },
  FAILED: {
    label: "Failed",
    icon: XCircle,
    className: "border-danger/40 bg-danger/10 text-danger",
  },
};

function shortRange(r: ReportRequest): string {
  const from = r.dateFrom ?? "—";
  const to = r.dateTo ?? "—";
  if (from === "—" && to === "—") return "Full portfolio";
  return `${from} → ${to}`;
}

export interface ReportRequestsTableProps {
  data: { items: ReportRequest[] } | undefined;
  isLoading: boolean;
  isDownloading?: boolean;
  downloadingId?: string | null;
  onDownload: (row: ReportRequest) => void;
  className?: string;
}

export function ReportRequestsTable({
  data,
  isLoading,
  isDownloading = false,
  downloadingId = null,
  onDownload,
  className,
}: ReportRequestsTableProps) {
  const items = data?.items ?? [];

  if (isLoading && items.length === 0) {
    return (
      <div data-slot="report-requests-table" className={cn("flex flex-col gap-2", className)}>
        <TableSkeleton rows={3} cols={5} />
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div
        data-slot="report-requests-table-empty"
        className={cn("border-border bg-surface rounded-md border", className)}
      >
        <EmptyState
          title="No reports queued yet"
          description="Generate a portfolio MIS report to see it appear here."
        />
      </div>
    );
  }

  return (
    <div
      data-slot="report-requests-table"
      className={cn(
        "border-border bg-surface shadow-e1 overflow-hidden rounded-md border",
        className,
      )}
      data-density="compact"
    >
      <Table aria-label="Report requests">
        <TableHeader className="bg-surface-muted/60">
          <TableRow className="border-border hover:bg-transparent">
            <TableHead className="text-foreground-muted h-8 px-2.5 text-[11px] font-medium tracking-wide uppercase">
              Status
            </TableHead>
            <TableHead className="text-foreground-muted h-8 px-2.5 text-[11px] font-medium tracking-wide uppercase">
              Range
            </TableHead>
            <TableHead className="text-foreground-muted h-8 px-2.5 text-[11px] font-medium tracking-wide uppercase">
              LSP
            </TableHead>
            <TableHead className="text-foreground-muted h-8 px-2.5 text-[11px] font-medium tracking-wide uppercase">
              Queued
            </TableHead>
            <TableHead className="text-foreground-muted h-8 px-2.5 text-right text-[11px] font-medium tracking-wide uppercase">
              <span className="sr-only">Actions</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((row) => {
            const meta = STATUS_META[row.status];
            const Icon = meta.icon;
            const isThisDownloading = isDownloading && downloadingId === row.id;
            return (
              <TableRow
                key={row.id}
                data-testid={`report-request-row-${row.id}`}
                data-status={row.status}
                className="border-border"
              >
                <TableCell className="px-2.5 py-1.5">
                  <Badge
                    data-slot="report-status-badge"
                    data-status={row.status}
                    className={cn("gap-1", meta.className)}
                  >
                    <Icon
                      aria-hidden="true"
                      className={cn("size-3", row.status === "PROCESSING" && "animate-spin")}
                    />
                    <span>{meta.label}</span>
                  </Badge>
                </TableCell>
                <TableCell className="text-foreground px-2.5 py-1.5 text-xs">
                  {shortRange(row)}
                </TableCell>
                <TableCell className="text-foreground-muted px-2.5 py-1.5 font-mono text-[11px]">
                  {row.lspId ? row.lspId.slice(0, 8) + "…" : "All"}
                </TableCell>
                <TableCell className="text-foreground-muted px-2.5 py-1.5 text-[11px] tabular-nums">
                  {formatDateTime(row.queuedAt)}
                </TableCell>
                <TableCell className="px-2.5 py-1.5 text-right">
                  {row.status === "COMPLETED" ? (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      data-slot="report-download-button"
                      data-testid={`report-download-${row.id}`}
                      onClick={() => onDownload(row)}
                      disabled={isThisDownloading}
                      className="gap-1"
                    >
                      <Download aria-hidden="true" className="size-3.5" />
                      {isThisDownloading ? "Opening…" : "Download"}
                    </Button>
                  ) : (
                    <span className="text-foreground-muted text-xs">—</span>
                  )}
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
