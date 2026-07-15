import { useState } from "react";
import { AlertTriangle } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { ActionBar } from "@/components/app/lifecycle/ActionBar";
import { EscalateToAdminDialog } from "@/components/app/lifecycle/EscalateToAdminDialog";
import { useSession } from "@/features/auth/session-context";
import { escalateAlert } from "@/features/alerts/api";
import { useInitiateDisbursement, useTransitionStatus } from "../hooks/useLoanApplicationMutations";
import { fetchLatestDisbursementReference } from "../api-detail";
import type { LoanApplicationDetail, TransitionStatusInput } from "../types";
import { mapApiErrorMessage, formatLoanStatusLabel } from "@/lib/api/user-messages";
import { shortId } from "@/lib/short-id";
import { ForeclosureQuotePanel } from "./ForeclosureQuotePanel";

export interface DetailHeaderProps {
  detail: LoanApplicationDetail;
  /** Optional callback fired after a successful transition (e.g. for toasts). */
  onTransitionSuccess?: () => void;
}

/**
 * Detail-page header: borrower name → status badge → ActionBar.
 */
export function DetailHeader({ detail, onTransitionSuccess }: DetailHeaderProps) {
  const { session } = useSession();
  const role = session?.user.role;
  const mutation = useTransitionStatus(detail.application.id);
  const disbursementMutation = useInitiateDisbursement(detail.application.id);
  const [escalateOpen, setEscalateOpen] = useState(false);
  const [escalateBusy, setEscalateBusy] = useState(false);

  const handleEscalate = async ({
    title,
    message,
    idempotencyKey,
  }: {
    title: string;
    message: string;
    idempotencyKey: string;
  }) => {
    setEscalateBusy(true);
    try {
      await escalateAlert({
        subjectType: "LOAN_APPLICATION",
        subjectId: detail.application.id,
        title,
        message,
        idempotencyKey,
      });
      toast.success("Escalation sent to admin.");
      setEscalateOpen(false);
    } catch (err) {
      const detailMsg = mapApiErrorMessage(err, "Please try again.");
      toast.error(`Failed to send escalation: ${detailMsg}`);
    } finally {
      setEscalateBusy(false);
    }
  };

  const fullName = detail.borrower.fullName;
  const externalLabel = detail.application.externalLoanId ?? "—";

  const handleConfirm = async ({
    action,
    reason,
    reasonCode,
    idempotencyKey,
  }: {
    action: { toStatus: TransitionStatusInput["to"]; label: string };
    reason: string | null;
    reasonCode: string | null;
    idempotencyKey: string;
  }) => {
    try {
      // Disbursement uses a dedicated backend endpoint; generic transitions
      // cannot move APPROVED_PENDING_DISBURSAL / DISBURSEMENT_RETRY → DISBURSED.
      if (
        action.toStatus === "DISBURSED" &&
        (detail.application.status === "APPROVED_PENDING_DISBURSAL" ||
          detail.application.status === "DISBURSEMENT_RETRY")
      ) {
        await disbursementMutation.mutateAsync({ note: reason, idempotencyKey });
        const reference = await fetchLatestDisbursementReference(detail.application.id);
        toast.success(
          reference
            ? `Disbursement requested. Reference: ${reference}.`
            : `Application moved to ${formatLoanStatusLabel(action.toStatus)}.`,
        );
      } else {
        await mutation.mutateAsync({
          to: action.toStatus,
          reason,
          reasonCode,
          idempotencyKey,
        });
      }
      if (action.toStatus !== "DISBURSED") {
        toast.success(`Application moved to ${formatLoanStatusLabel(action.toStatus)}.`);
      }
      onTransitionSuccess?.();
    } catch (err) {
      const detailMsg = mapApiErrorMessage(err);
      toast.error(`Could not ${action.label.toLowerCase()}: ${detailMsg}`);
      // Re-throw so ActionBar surfaces the failure in its aria-live region.
      throw err;
    }
  };

  return (
    <div data-slot="detail-header" className="flex flex-col gap-4">
      <PageHeader
        eyebrow={`Loan application · ${externalLabel}`}
        title={fullName || "Borrower"}
        description={`Application ${shortId(detail.application.id)}`}
        actions={
          <div className="flex items-center gap-2">
            <StatusBadge
              status={detail.application.status}
              delinquency={detail.accountDelinquency}
            />
          </div>
        }
      />

      {role === "OPS_USER" ? (
        <div data-slot="ops-escalate-bar" className="flex flex-col gap-2">
          <p className="text-foreground-muted text-sm">
            Approvals and lifecycle changes are automated. Use Escalate to admin if this loan needs
            out-of-band intervention.
          </p>
          <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Loan actions">
            <Button
              type="button"
              variant="outline"
              onClick={() => setEscalateOpen(true)}
              data-action-id="OPS_ESCALATE_TO_ADMIN"
            >
              <AlertTriangle aria-hidden="true" className="size-4" />
              <span>Escalate to admin</span>
            </Button>
          </div>
          <EscalateToAdminDialog
            open={escalateOpen}
            onOpenChange={(next) => {
              if (escalateBusy) return;
              setEscalateOpen(next);
            }}
            subjectType="LOAN_APPLICATION"
            subjectId={detail.application.id}
            onConfirm={handleEscalate}
            loading={escalateBusy}
          />
        </div>
      ) : role ? (
        <ActionBar
          currentStatus={detail.application.status}
          role={role}
          applicationId={detail.application.id}
          gates={{
            docsComplete: detail.docsComplete,
            scheduleValid: detail.scheduleValid,
          }}
          onConfirm={handleConfirm}
          hiddenTargetStatuses={["FORECLOSED"]}
        />
      ) : null}

      {role === "SYSTEM_ADMIN" ? (
        <ForeclosureQuotePanel detail={detail} onExecuted={onTransitionSuccess} />
      ) : null}
    </div>
  );
}

export default DetailHeader;
