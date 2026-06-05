import { ShieldCheck, ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { BorrowerDetail } from "../types";

export interface BorrowerHeaderProps {
  detail: BorrowerDetail;
}

/**
 * Borrower-detail page header — eyebrow, full name, KYC badge, visible-LSP
 * count, and the open-application tally. Identity numbers (PAN, Aadhaar,
 * account etc.) are always masked at the API; this header only shows
 * non-PII attributes.
 */
export function BorrowerHeader({ detail }: BorrowerHeaderProps) {
  const { borrower, visibleLsps, totals } = detail;
  const eyebrow = `Borrower · ${borrower.id.slice(0, 8)}…`;

  return (
    <div data-slot="borrower-header" className="flex flex-col gap-3">
      <PageHeader
        eyebrow={eyebrow}
        title={borrower.fullName}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Badge
              data-slot="kyc-badge"
              data-state={borrower.kycComplete ? "complete" : "incomplete"}
              className={cn(
                "border",
                borrower.kycComplete
                  ? "border-success/30 bg-success/10 text-success"
                  : "border-warning/30 bg-warning/10 text-warning",
              )}
            >
              {borrower.kycComplete ? (
                <ShieldCheck className="mr-1 size-3.5" aria-hidden="true" />
              ) : (
                <ShieldAlert className="mr-1 size-3.5" aria-hidden="true" />
              )}
              {borrower.kycComplete ? "KYC complete" : "KYC pending"}
            </Badge>
            <Badge data-slot="lsp-count-badge" variant="outline" className="border-border">
              {visibleLsps.length === 1 ? "1 LSP" : `${visibleLsps.length} LSPs`}
            </Badge>
          </div>
        }
      />
      <p className="text-foreground-muted text-sm" data-slot="open-app-count">
        {totals.openApplicationsCount === 0
          ? "No open applications"
          : totals.openApplicationsCount === 1
            ? "1 open application"
            : `${totals.openApplicationsCount} open applications`}
      </p>
    </div>
  );
}

export default BorrowerHeader;
