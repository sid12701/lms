import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { LoanStatus, Role } from "@/types";
import { actionsFor, type LifecycleAction } from "./actions";
import {
  TransitionDisabledTooltip,
  resolveDisabledReason,
  type TransitionGates,
} from "./TransitionDisabledTooltip";
import { TransitionConfirmDialog } from "./TransitionConfirmDialog";
import {
  fetchDisbursementPreview,
  type DisbursementPreviewResponse,
} from "@/features/loan-applications/api-detail";

export interface ActionBarProps {
  currentStatus: LoanStatus;
  role: Role;
  /** Loan application id — required for disbursement preview on DISBURSED actions. */
  applicationId?: string;
  /** Externally-evaluated business gates surfaced as "disabled with reason". */
  gates?: TransitionGates;
  /** Hide transitions that are handled by a dedicated workflow on the host screen. */
  hiddenTargetStatuses?: readonly LoanStatus[];
  onConfirm: (args: {
    action: LifecycleAction;
    reason: string | null;
    reasonCode: string | null;
    idempotencyKey: string;
  }) => Promise<void> | void;
  className?: string;
}

/**
 * Approve is the primary action, not a green one.
 *
 * A solid `--success` fill used a *semantic intent* as an action colour.
 * DESIGN.md is explicit that the seven intents "are not decorative colours and
 * must not be reused as a palette" — success means paid, closed, credited,
 * healthy: a state the record is *in*, not a button you can press. Approving is
 * simply the consequential action on this view, which is what the One Blue Rule
 * describes. The green now stays available to say what the loan became.
 */
const TONE_BUTTON: Record<LifecycleAction["tone"], string> = {
  default: "",
  destructive: "",
  approve: "",
};

const TONE_BUTTON_DISABLED: Record<LifecycleAction["tone"], string> = {
  default: "",
  destructive: "",
  approve:
    "bg-surface-muted text-foreground-muted hover:bg-surface-muted border-border border opacity-70",
};

const NO_HIDDEN_TARGET_STATUSES: readonly LoanStatus[] = [];

/**
 * Renders every UI-actionable transition leaving `currentStatus`.
 *
 * Each action is wrapped in `TransitionDisabledTooltip`; the disabled reason
 * is computed from `canTransition()` plus the supplied `gates`. Clicking
 * an enabled action opens `TransitionConfirmDialog`; the dialog forwards
 * a fresh idempotency key + trimmed reason to `onConfirm`.
 *
 * Status of the most recent submit is announced via an `aria-live="polite"`
 * region so AT users hear success / failure without re-reading the page.
 */
export function ActionBar({
  currentStatus,
  role,
  applicationId,
  gates,
  hiddenTargetStatuses = NO_HIDDEN_TARGET_STATUSES,
  onConfirm,
  className,
}: ActionBarProps) {
  const [activeAction, setActiveAction] = useState<LifecycleAction | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [statusMessage, setStatusMessage] = useState("");
  const [dialogError, setDialogError] = useState<string | null>(null);

  const { items, allActionsRelocated } = useMemo(() => {
    const hiddenTargets = new Set(hiddenTargetStatuses);
    const all = actionsFor(currentStatus);
    const visible = all.filter((action) => !hiddenTargets.has(action.toStatus));
    return {
      items: visible.map((action) => ({
        action,
        disabledReason: resolveDisabledReason(action, currentStatus, role, gates),
      })),
      // Every action leaving this status is owned by a dedicated workflow on the
      // host screen. The bar is empty, but the screen is not — so it must not
      // claim otherwise.
      allActionsRelocated: visible.length === 0 && all.length > 0,
    };
  }, [currentStatus, role, gates, hiddenTargetStatuses]);

  const handleClick = (action: LifecycleAction) => {
    setActiveAction(action);
    setDialogError(null);
    setOpen(true);
  };

  const handleConfirm = async (args: {
    action: LifecycleAction;
    reason: string | null;
    reasonCode: string | null;
    idempotencyKey: string;
  }) => {
    setBusy(true);
    setDialogError(null);
    try {
      await onConfirm(args);
      setStatusMessage(`Action "${args.action.label}" completed.`);
      setOpen(false);
      setActiveAction(null);
    } catch (e) {
      const detail = e instanceof Error ? e.message : "Please try again.";
      setStatusMessage(`Action "${args.action.label}" failed: ${detail}`);
      // Keep the dialog open and show the failure where the user is looking.
      setDialogError(detail);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-slot="action-bar" className={cn("flex flex-col gap-2", className)}>
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Loan actions">
        {items.length === 0 ? (
          allActionsRelocated ? null : (
            <p className="text-foreground-muted text-sm" data-slot="action-bar-empty">
              No actions available from this status.
            </p>
          )
        ) : (
          items.map(({ action, disabledReason }) => {
            const isDisabled = disabledReason !== null;
            const variant: "default" | "destructive" | "outline" =
              action.tone === "destructive"
                ? "destructive"
                : action.tone === "approve"
                  ? "default"
                  : "outline";
            return (
              <TransitionDisabledTooltip key={action.id} disabledReason={disabledReason}>
                <Button
                  type="button"
                  variant={variant}
                  className={cn(
                    isDisabled ? TONE_BUTTON_DISABLED[action.tone] : TONE_BUTTON[action.tone],
                  )}
                  onClick={() => handleClick(action)}
                  data-action-id={action.id}
                  data-tone={action.tone}
                >
                  {action.label}
                </Button>
              </TransitionDisabledTooltip>
            );
          })
        )}
      </div>

      <span data-slot="action-bar-status" aria-live="polite" aria-atomic="true" className="sr-only">
        {statusMessage}
      </span>

      <TransitionConfirmDialog
        open={open}
        onOpenChange={(next) => {
          if (busy) return;
          setOpen(next);
          if (!next) {
            setActiveAction(null);
            setDialogError(null);
          }
        }}
        action={activeAction}
        applicationId={applicationId}
        loadDisbursementPreview={loadDisbursementPreviewForDialog}
        onConfirm={handleConfirm}
        loading={busy}
        errorMessage={dialogError}
      />
    </div>
  );
}

function mapDisbursementPreview(response: DisbursementPreviewResponse) {
  return {
    principal: response.principal,
    processingFee: response.processingFee,
    netDisbursalAmount: response.netDisbursalAmount,
    paymentMode: response.paymentMode,
    beneficiaryAccountHolderName: response.beneficiaryAccountHolderName,
    beneficiaryBankName: response.beneficiaryBankName,
    beneficiaryIfsc: response.beneficiaryIfsc,
    maskedBeneficiaryAccountNumber: response.maskedBeneficiaryAccountNumber,
    externalLoanId: response.externalLoanId,
    loanAccountNumber: response.loanAccountNumber,
    beneficiarySource: response.beneficiarySource,
    pendingIntentTranRefNo: response.pendingIntentTranRefNo,
  };
}

const loadDisbursementPreviewForDialog = (id: string) =>
  fetchDisbursementPreview(id).then(mapDisbursementPreview);
