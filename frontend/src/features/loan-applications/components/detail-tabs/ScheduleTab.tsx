/**
 * Schedule tab — renders the repayment schedule for a loan application.
 *
 * Density: COMPACT per D7 (long-list surface). The `data-density="compact"`
 * wrapper opts the contained primitives into the compact token set.
 *
 * BR-3 + BR-10 gates are surfaced via `DisbursementGateBanner` whenever the
 * application is in `APPROVED_PENDING_DISBURSAL` or `DISBURSEMENT_IN_PROGRESS`;
 * the banner self-hides when both gates pass.
 *
 * BR-13 is enforced by the dialog schema (`makeRepaymentPostSchema`) and
 * re-checked server-side by the backend.
 */
import { useMemo, useState } from "react";
import { CalendarClock, Inbox } from "lucide-react";
import { toast } from "sonner";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { DisbursementGateBanner } from "@/components/app/disbursement/DisbursementGateBanner";
import {
  RepaymentPostDialog,
  ScheduleTable,
  type RepaymentPostConfirmArgs,
} from "@/components/app/repayment";
import type { LoanStatus, RepaymentInstallment } from "@/types";
import { useLoanApplicationSchedule } from "../../hooks/useLoanApplicationSchedule";
import { usePostRepayment } from "../../hooks/usePostRepayment";

export interface ScheduleTabProps {
  applicationId: string;
  status: LoanStatus;
  /** When true, the user may post repayments against installments. */
  canPost: boolean;
  /** BR-3 — every required-for-disbursement document is uploaded. */
  docsComplete: boolean;
  /** BR-10 — a valid repayment schedule exists. */
  scheduleValid: boolean;
}

/** Statuses where the disbursement gate is informative to the user. */
const PRE_DISBURSEMENT_STATUSES = new Set<LoanStatus>([
  "APPROVED_PENDING_DISBURSAL",
  "DISBURSEMENT_IN_PROGRESS",
]);

export function ScheduleTab({
  applicationId,
  status,
  canPost,
  docsComplete,
  scheduleValid,
}: ScheduleTabProps) {
  const query = useLoanApplicationSchedule(applicationId);
  const postMutation = usePostRepayment(applicationId);

  const [selected, setSelected] = useState<RepaymentInstallment | null>(null);

  const installments = useMemo<RepaymentInstallment[]>(
    () => (query.data?.installments ? [...query.data.installments] : []),
    [query.data],
  );

  const showGateBanner = PRE_DISBURSEMENT_STATUSES.has(status);
  const showAwaitingScheduleBanner =
    status === "APPROVED_PENDING_DISBURSAL" && installments.length === 0;
  const showInvalidScheduleBanner = installments.length > 0 && !scheduleValid;

  const handleOpenDialog = canPost
    ? (installment: RepaymentInstallment) => {
        setSelected(installment);
      }
    : undefined;

  const handleConfirm = async (args: RepaymentPostConfirmArgs) => {
    if (!selected) return;
    try {
      await postMutation.mutateAsync({
        installmentId: selected.id,
        amount: args.amount,
        postedAt: args.postedAt,
        mode: args.mode,
        reference: args.reference,
        idempotencyKey: args.idempotencyKey,
      });
      toast.success("Repayment posted");
      setSelected(null);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Failed to post repayment";
      toast.error(message);
    }
  };

  return (
    <div data-slot="schedule-tab" data-density="compact" className="flex flex-col gap-4">
      {showGateBanner ? (
        <DisbursementGateBanner docsComplete={docsComplete} scheduleValid={scheduleValid} />
      ) : null}

      {query.isPending ? (
        <TableSkeleton
          rows={6}
          cols={8}
          className="opacity-100 transition-opacity duration-200 motion-reduce:transition-none"
        />
      ) : query.isError ? (
        <ErrorState
          title="Couldn't load repayment schedule"
          description={query.error instanceof Error ? query.error.message : undefined}
          retry={{ onClick: () => void query.refetch() }}
        />
      ) : showAwaitingScheduleBanner ? (
        <div
          data-slot="awaiting-schedule-banner"
          role="status"
          className="border-info/30 bg-info/5 text-foreground flex items-start gap-3 rounded-md border p-4 text-sm"
        >
          <Inbox aria-hidden="true" className="text-info mt-0.5 h-5 w-5 shrink-0" />
          <div className="flex flex-col gap-1">
            <p className="font-semibold">Awaiting LSP schedule</p>
            <p className="text-foreground-muted">
              This application is approved but no repayment schedule has been submitted by the LSP
              yet. Use the dev tools to simulate an LSP POST, or wait for the LSP backend to
              deliver.
            </p>
          </div>
        </div>
      ) : installments.length === 0 ? (
        <EmptyState
          icon={CalendarClock}
          title="No repayment schedule yet"
          description="A schedule is generated once the loan moves to APPROVED_PENDING_DISBURSAL."
        />
      ) : (
        <>
          {showInvalidScheduleBanner ? (
            <ErrorState
              data-slot="invalid-schedule-banner"
              title="Schedule validation failed"
              description="The LSP-submitted schedule does not reconcile to the approved principal (BR-11). The LSP must resubmit a corrected schedule."
            />
          ) : null}
          <ScheduleTable
            installments={installments}
            {...(handleOpenDialog ? { onRecordPayment: handleOpenDialog } : {})}
          />
        </>
      )}

      <RepaymentPostDialog
        open={Boolean(selected)}
        onOpenChange={(open) => {
          if (!open) setSelected(null);
        }}
        outstandingAmount={selected?.outstandingAmount ?? 0}
        installmentLabel={selected ? `Installment ${selected.number}` : undefined}
        onConfirm={handleConfirm}
        loading={postMutation.isPending}
      />
    </div>
  );
}

export default ScheduleTab;
