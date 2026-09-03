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
 *
 * This panel had no fill. It asked for `bg-card-muted/40`, but
 * `--color-card-muted` was defined nowhere, so the computed background was
 * `rgba(0,0,0,0)` — the money-verification block for the only irreversible
 * action in the system rendered as an empty rectangle with a hairline. The
 * token now exists (`tokens.css`) and the fill is applied at full strength:
 * this is a distinct surface the operator is being asked to *check*, not
 * another paragraph of dialog body.
 */
export function DisbursementPreviewSummary({ preview }: DisbursementPreviewSummaryProps) {
  return (
    <div
      data-slot="disbursement-preview"
      className="border-border-strong bg-card-muted rounded-container border p-3"
      aria-label="Disbursement summary"
    >
      <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        <div className="flex flex-col gap-0.5 sm:col-span-2">
          <dt className="text-foreground-muted text-eyebrow uppercase">Principal</dt>
          <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
            {formatINR(preview.principal, { decimals: 2 })}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-eyebrow uppercase">Processing fee</dt>
          <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
            {formatINR(preview.processingFee, { decimals: 2 })}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-eyebrow uppercase">Net disbursal</dt>
          <dd className="text-foreground font-semibold" {...TABULAR_ATTR}>
            {formatINR(preview.netDisbursalAmount, { decimals: 2 })}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-eyebrow uppercase">Payment mode</dt>
          <dd className="text-foreground font-medium">{preview.paymentMode}</dd>
        </div>
        {preview.externalLoanId ? (
          <div className="flex flex-col gap-0.5">
            <dt className="text-foreground-muted text-eyebrow uppercase">External loan ID</dt>
            <dd className="text-foreground font-medium">{preview.externalLoanId}</dd>
          </div>
        ) : null}
        <div className="border-border col-span-full my-1 border-t" />
        <div className="flex flex-col gap-0.5 sm:col-span-2">
          <dt className="text-foreground-muted text-eyebrow uppercase">Beneficiary</dt>
          <dd className="text-foreground font-medium">{preview.beneficiaryAccountHolderName}</dd>
          {preview.beneficiarySource === "LIVE_BORROWER" ? (
            <dd className="text-foreground-muted text-xs">
              Read from the borrower&apos;s current bank details — these may have changed since
              approval.
            </dd>
          ) : null}
        </div>
        {preview.beneficiaryBankName ? (
          <div className="flex flex-col gap-0.5">
            <dt className="text-foreground-muted text-eyebrow uppercase">Bank</dt>
            <dd className="text-foreground font-medium">{preview.beneficiaryBankName}</dd>
          </div>
        ) : null}
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-eyebrow uppercase">Account number</dt>
          <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
            {preview.maskedBeneficiaryAccountNumber}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-foreground-muted text-eyebrow uppercase">IFSC</dt>
          <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
            {preview.beneficiaryIfsc}
          </dd>
        </div>
        {preview.pendingIntentTranRefNo ? (
          <div className="flex flex-col gap-0.5 sm:col-span-2">
            <dt className="text-foreground-muted text-eyebrow uppercase">
              Pending intent reference
            </dt>
            <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
              {preview.pendingIntentTranRefNo}
            </dd>
          </div>
        ) : null}
      </dl>

      {/*
        The point of no return, stated on screen.
        The audit found that the irreversibility of the debit leg appeared
        nowhere in the copy — the dialog explained that the change would be
        audited, but never that it could not be undone. An operator cannot
        exercise caution about a property they have not been told about.
      */}
      <p
        data-slot="disbursement-irreversible-notice"
        className="border-border text-foreground-muted mt-3 border-t pt-2 text-xs"
      >
        <span className="text-foreground font-medium">This cannot be undone.</span> Once the debit
        leg is placed with the bank, the transfer cannot be recalled or re-initiated from this
        panel. Check the net amount and the beneficiary account against the source document before
        you confirm.
      </p>
    </div>
  );
}
