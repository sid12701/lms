/**
 * Read-only audit trail for LSP status changes (`GET …/audit-events`).
 */
import { History } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatDateTime } from "@/lib/format";
import type { LspAuditEventRow, LspRow } from "../types";
import { LSP_STATUS_CHANGE_REASON_OPTIONS } from "./lspStatusLabels";

export interface LspAuditEventsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lsp: LspRow | null;
  events: LspAuditEventRow[] | undefined;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
}

function reasonLabel(reason: LspAuditEventRow["reason"]): string {
  if (!reason) return "—";
  return LSP_STATUS_CHANGE_REASON_OPTIONS.find((o) => o.value === reason)?.label ?? reason;
}

function actionLabel(action: string): string {
  switch (action) {
    case "LSP_DISABLED":
      return "Disabled";
    case "LSP_REACTIVATED":
      return "Reactivated";
    default:
      return action;
  }
}

export function LspAuditEventsDialog({
  open,
  onOpenChange,
  lsp,
  events,
  isLoading,
  isError,
  onRetry,
}: LspAuditEventsDialogProps) {
  const rows = events ?? [];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <History className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>Status audit trail</DialogTitle>
          </div>
          <DialogDescription>
            {lsp ? (
              <>
                Status change history for <span className="font-mono font-medium">{lsp.code}</span>.
              </>
            ) : (
              "Recorded disable and reactivate actions for this LSP."
            )}
          </DialogDescription>
        </DialogHeader>

        {isError ? (
          <ErrorState
            title="Couldn't load audit events"
            description="The audit trail could not be fetched. Try again."
            retry={{ label: "Retry", onClick: onRetry }}
          />
        ) : isLoading ? (
          <p className="text-foreground-muted text-sm">Loading audit events…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="No status changes yet"
            description="Disable or reactivate actions will appear here with reason, note, and actor."
          />
        ) : (
          <div className="max-h-[min(24rem,50vh)] overflow-auto rounded-md border">
            <table className="w-full text-sm" data-slot="lsp-audit-table">
              <thead className="bg-surface-muted sticky top-0">
                <tr className="text-foreground-muted text-left text-xs">
                  <th className="px-3 py-2 font-medium">When</th>
                  <th className="px-3 py-2 font-medium">Action</th>
                  <th className="px-3 py-2 font-medium">Reason</th>
                  <th className="px-3 py-2 font-medium">Actor</th>
                  <th className="px-3 py-2 font-medium">Clients</th>
                  <th className="px-3 py-2 font-medium">Note</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td
                      className="text-foreground-muted px-3 py-2 align-top text-xs whitespace-nowrap"
                      title={formatDateTime(row.createdAt)}
                    >
                      {formatDateTime(row.createdAt)}
                    </td>
                    <td className="px-3 py-2 align-top font-medium">{actionLabel(row.action)}</td>
                    <td className="px-3 py-2 align-top">{reasonLabel(row.reason)}</td>
                    <td className="px-3 py-2 align-top font-mono text-xs">{row.actorUsername}</td>
                    <td className="px-3 py-2 align-top tabular-nums">
                      {row.cascadedClientCount > 0 ? row.cascadedClientCount : "—"}
                    </td>
                    <td className="text-foreground-muted px-3 py-2 align-top text-xs">
                      {row.note ?? "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
