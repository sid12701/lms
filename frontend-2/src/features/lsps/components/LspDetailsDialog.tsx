/**
 * Read-only LSP summary with shortcuts to status change and audit trail.
 */
import { Building2 } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { LspRow } from "../types";
import type { LspStatus } from "@/schemas/lsp";

const STATUS_LABEL: Record<LspStatus, string> = {
  ACTIVE: "Active",
  SUSPENDED: "Suspended",
  INACTIVE: "Inactive",
};

export interface LspDetailsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lsp: LspRow | null;
  onChangeStatus: () => void;
  onViewAudit: () => void;
  onManageIpAllowlists: () => void;
}

export function LspDetailsDialog({
  open,
  onOpenChange,
  lsp,
  onChangeStatus,
  onViewAudit,
  onManageIpAllowlists,
}: LspDetailsDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Building2 className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>LSP details</DialogTitle>
          </div>
          <DialogDescription>
            Tenant identifiers are immutable. Use status change for disable / reactivate.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <Label htmlFor="lsp-details-code">Code</Label>
            <Input id="lsp-details-code" value={lsp?.code ?? ""} readOnly disabled />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="lsp-details-name">Display name</Label>
            <Input id="lsp-details-name" value={lsp?.name ?? ""} readOnly disabled />
          </div>
          <div className="flex flex-col gap-1">
            <Label>Status</Label>
            <div>
              <Badge
                data-slot="lsp-details-status"
                className={cn(
                  lsp?.status === "ACTIVE" && "border-success/30 bg-success/10 text-success",
                  lsp?.status === "INACTIVE" &&
                    "border-border bg-surface-muted text-foreground-muted",
                  lsp?.status === "SUSPENDED" && "border-warning/30 bg-warning/10 text-warning",
                )}
              >
                {lsp ? STATUS_LABEL[lsp.status] : "—"}
              </Badge>
            </div>
          </div>
        </div>

        <DialogFooter className="flex-col gap-2 sm:flex-row sm:justify-between">
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="secondary" onClick={onChangeStatus} disabled={!lsp}>
              Change status
            </Button>
            <Button type="button" variant="outline" onClick={onViewAudit} disabled={!lsp}>
              Audit trail
            </Button>
            <Button type="button" variant="outline" onClick={onManageIpAllowlists} disabled={!lsp}>
              IP allowlists
            </Button>
          </div>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
