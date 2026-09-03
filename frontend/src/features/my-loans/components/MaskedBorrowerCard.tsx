import { ShieldAlert } from "lucide-react";
import type { MyLoanDetail } from "../api";
import { DetailField } from "./DetailField";

export interface MaskedBorrowerCardProps {
  detail: MyLoanDetail;
}

/**
 * Read-only borrower identity summary. The backend serves Aadhaar masked;
 * PAN is returned as submitted by this LSP (they originated it), so the
 * card must not promise blanket masking (audit LSP pass — the old copy
 * said "masked everywhere" directly above a cleartext PAN).
 */
export function MaskedBorrowerCard({ detail }: MaskedBorrowerCardProps) {
  return (
    <section
      data-slot="masked-borrower-card"
      className="border-border bg-background rounded-container flex flex-col gap-4 border p-5"
    >
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <ShieldAlert className="text-warning h-4 w-4" aria-hidden="true" />
            <h2 className="text-base font-semibold">Borrower identity</h2>
          </div>
          <p className="text-foreground-muted text-xs">
            Aadhaar is shown masked. Handle identity details per your data-processing agreement;
            re-issue the application if the borrower&apos;s identity needs to be re-verified.
          </p>
        </div>
      </header>

      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
        <DetailField label="Aadhaar" value={detail.borrowerAadhaarMasked} mono />
        <DetailField label="PAN" value={detail.borrowerPanMasked} mono />
      </dl>
    </section>
  );
}
