import { ShieldAlert } from "lucide-react";
import type { MyLoanDetail } from "../api";
import { DetailField } from "./DetailField";

export interface MaskedBorrowerCardProps {
  detail: MyLoanDetail;
}

/**
 * Read-only borrower PII summary. Per the masking-everywhere posture
 * (see `docs/gap-fixes.md` § Gap #1), PII fields are never unmasked at
 * the API or UI layer — the backend serves masked values; the FE
 * displays them as-is.
 */
export function MaskedBorrowerCard({ detail }: MaskedBorrowerCardProps) {
  return (
    <section
      data-slot="masked-borrower-card"
      className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
    >
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <ShieldAlert className="text-warning h-4 w-4" aria-hidden="true" />
            <h2 className="text-base font-semibold">Borrower PII (masked)</h2>
          </div>
          <p className="text-foreground-muted text-xs">
            Identity numbers are masked everywhere. Re-issue the application if the borrower&apos;s
            identity needs to be re-verified.
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
