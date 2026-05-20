import { useState } from "react";
import { Eye, EyeOff } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { ActionBar } from "@/components/app/lifecycle/ActionBar";
import { useSession } from "@/features/auth/session-context";
import {
  useInitiateDisbursement,
  useTransitionStatus,
} from "../hooks/useLoanApplicationMutations";
import type { LoanApplicationDetail, TransitionStatusInput } from "../types";

export interface DetailHeaderProps {
  detail: LoanApplicationDetail;
  /** Optional callback fired after a successful transition (e.g. for toasts). */
  onTransitionSuccess?: () => void;
}

/**
 * Mask a borrower's full name to the first character + bullets per surname.
 * "Aanya Devi" → "A•••• D•••". Keeps initials visible so collisions are
 * still distinguishable in the audit log; reveal flips to clear text.
 */
function maskName(name: string): string {
  if (!name) return "";
  return name
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => `${part.charAt(0)}${"•".repeat(Math.max(2, part.length - 1))}`)
    .join(" ");
}

/**
 * Detail-page header: eyebrow → masked borrower name (revealable) → status
 * badge → ActionBar. The ActionBar wires `onConfirm` to `useTransitionStatus`
 * so the BR-5 idempotency key minted by `TransitionConfirmDialog` flows
 * straight through to the mock router.
 */
export function DetailHeader({ detail, onTransitionSuccess }: DetailHeaderProps) {
  const { session } = useSession();
  const role = session?.user.role;
  const mutation = useTransitionStatus(detail.application.id);
  const disbursementMutation = useInitiateDisbursement(detail.application.id);
  const [nameRevealed, setNameRevealed] = useState(false);

  const fullName = detail.borrower.fullName;
  const displayName = nameRevealed ? fullName : maskName(fullName);
  const externalLabel = detail.application.externalLoanId ?? "—";

  const handleConfirm = async ({
    action,
    reason,
    idempotencyKey,
  }: {
    action: { toStatus: TransitionStatusInput["to"]; label: string };
    reason: string | null;
    idempotencyKey: string;
  }) => {
    try {
      // The mock router exposes a dedicated `initiateDisbursement` handler
      // that schedules the bank-confirm callback (DISBURSEMENT_IN_PROGRESS →
      // DISBURSED) the generic `transition` endpoint does not. Route through
      // it so the loan can actually reach a repayable state from the UI.
      if (action.toStatus === "DISBURSEMENT_IN_PROGRESS") {
        await disbursementMutation.mutateAsync({ note: reason, idempotencyKey });
      } else {
        await mutation.mutateAsync({
          to: action.toStatus,
          reason,
          idempotencyKey,
        });
      }
      toast.success(`Application moved to ${action.toStatus.replace(/_/g, " ").toLowerCase()}.`);
      onTransitionSuccess?.();
    } catch (err) {
      const detailMsg = err instanceof Error ? err.message : "Please try again.";
      toast.error(`Failed to ${action.label.toLowerCase()}: ${detailMsg}`);
      // Re-throw so ActionBar surfaces the failure in its aria-live region.
      throw err;
    }
  };

  return (
    <div data-slot="detail-header" className="flex flex-col gap-4">
      <PageHeader
        eyebrow={`Loan application · ${externalLabel}`}
        title={displayName || "Borrower"}
        description={`Application ${detail.application.id}`}
        actions={
          <div className="flex items-center gap-2">
            <StatusBadge status={detail.application.status} />
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => setNameRevealed((prev) => !prev)}
              aria-label={nameRevealed ? "Hide borrower name" : "Reveal borrower name"}
              aria-pressed={nameRevealed}
            >
              {nameRevealed ? (
                <EyeOff aria-hidden="true" className="size-4" />
              ) : (
                <Eye aria-hidden="true" className="size-4" />
              )}
              <span>{nameRevealed ? "Hide" : "Reveal"}</span>
            </Button>
          </div>
        }
      />

      {role ? (
        <ActionBar
          currentStatus={detail.application.status}
          role={role}
          gates={{
            docsComplete: detail.docsComplete,
            scheduleValid: detail.scheduleValid,
          }}
          onConfirm={handleConfirm}
        />
      ) : null}
    </div>
  );
}

export default DetailHeader;
