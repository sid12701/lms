import { formatINR } from "@/lib/format";
import { TABULAR_ATTR } from "@/lib/tabular-nums";

export interface DisbursementPreviewData {
  principal: number;
  processingFee: number;
  netDisbursalAmount: number;
  paymentMode: string;
  beneficiaryAccountHolderName: string;
  beneficiaryBankName?: string | null;
  beneficiaryIfsc: string;
  maskedBeneficiaryAccountNumber: string;
  externalLoanId?: string | null;
  loanAccountNumber?: string | null;
  beneficiarySource?: string | null;
  pendingIntentTranRefNo?: string | null;
}

export interface DisbursementPreviewSummaryProps {
  preview: DisbursementPreviewData;
}

/**
 * Immutable money + beneficiary table shown before disbursement confirmation.
 */
export function DisbursementPreviewSummary({ preview }: DisbursementPreviewSummaryProps) {
  return (
    <div
      data-slot="disbursement-preview"
      className="border-border bg-card-muted/40 rounded-md border p-3"
      aria-label="Disbursement summary"
    >
      <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        <div className="flex flex-col gap-0.5 sm:col-span-2">
          <dt className="text-foreground-muted text-xs tracking-wide uppercase">Principal</dt>
          <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
            {formatINR(preview.principal, { decimals: 2 })}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-xs tracking-wide uppercase">Processing fee</dt>
          <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
            {formatINR(preview.processingFee, { decimals: 2 })}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-xs tracking-wide uppercase">Net disbursal</dt>
          <dd className="text-foreground font-semibold" {...TABULAR_ATTR}>
            {formatINR(preview.netDisbursalAmount, { decimals: 2 })}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-xs tracking-wide uppercase">Payment mode</dt>
          <dd className="text-foreground font-medium">{preview.paymentMode}</dd>
        </div>
        {preview.externalLoanId ? (
          <div className="flex flex-col gap-0.5">
            <dt className="text-foreground-muted text-xs tracking-wide uppercase">
              External loan ID
            </dt>
            <dd className="text-foreground font-medium">{preview.externalLoanId}</dd>
          </div>
        ) : null}
        <div className="border-border col-span-full my-1 border-t" />
        <div className="flex flex-col gap-0.5 sm:col-span-2">
          <dt className="text-foreground-muted text-xs tracking-wide uppercase">Beneficiary</dt>
          <dd className="text-foreground font-medium">{preview.beneficiaryAccountHolderName}</dd>
          {preview.beneficiarySource === "LIVE_BORROWER" ? (
            <dd className="text-foreground-muted text-xs">
              Current borrower bank details (live). Approval-time freeze is Spec S5.
            </dd>
          ) : null}
        </div>
        {preview.beneficiaryBankName ? (
          <div className="flex flex-col gap-0.5">
            <dt className="text-foreground-muted text-xs tracking-wide uppercase">Bank</dt>
            <dd className="text-foreground font-medium">{preview.beneficiaryBankName}</dd>
          </div>
        ) : null}
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-xs tracking-wide uppercase">Account number</dt>
          <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
            {preview.maskedBeneficiaryAccountNumber}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-xs tracking-wide uppercase">IFSC</dt>
          <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
            {preview.beneficiaryIfsc}
          </dd>
        </div>
        {preview.pendingIntentTranRefNo ? (
          <div className="flex flex-col gap-0.5 sm:col-span-2">
            <dt className="text-foreground-muted text-xs tracking-wide uppercase">
              Pending intent reference
            </dt>
            <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
              {preview.pendingIntentTranRefNo}
            </dd>
          </div>
        ) : null}
      </dl>
    </div>
  );
}
