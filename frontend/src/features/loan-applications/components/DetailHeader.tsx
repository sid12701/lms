import { useState } from "react";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { AbsoluteRelativeTime } from "@/components/app/misc/AbsoluteRelativeTime";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { ActionBar } from "@/components/app/lifecycle/ActionBar";
import { ManualStatusOverrideDialog } from "@/components/app/lifecycle/ManualStatusOverrideDialog";
import { EscalateToAdminDialog } from "@/components/app/lifecycle/EscalateToAdminDialog";
import { useSession } from "@/features/auth/session-context";
import { escalateAlert } from "@/features/alerts/api";
import {
  useInitiateDisbursement,
  useManualStatusOverride,
  useTransitionStatus,
} from "../hooks/useLoanApplicationMutations";
import {
  fetchLatestDisbursementReference,
  isManualOverrideSourceBlocked,
  manualOverrideTargetsFor,
} from "../api-detail";
import type { LoanApplicationDetail, TransitionStatusInput } from "../types";
import { mapApiErrorMessage, formatLoanStatusLabel } from "@/lib/api/user-messages";
import { isLoanApplicationStatus } from "@/lib/loan-application-status";
import { cn } from "@/lib/utils";
import { shortId } from "@/lib/short-id";
import { ForeclosureQuotePanel } from "./ForeclosureQuotePanel";
import type { LoanStatus, Role } from "@/types";

/** Stable identity so `ActionBar`'s memo does not re-run every render. */
const FORECLOSURE_OWNED_BY_PANEL: readonly LoanStatus[] = ["FORECLOSED"];

export interface DetailHeaderProps {
  detail: LoanApplicationDetail;
  /** Optional callback fired after a successful transition (e.g. for toasts). */
  onTransitionSuccess?: () => void;
  /**
   * L02 — when `onRefresh` is provided the header shows a last-updated
   * timestamp and a manual refresh affordance, so an operator watching an
   * in-flight disbursement can see data age and force a refetch.
   */
  lastUpdatedAt?: number;
  isRefreshing?: boolean;
  onRefresh?: () => void;
}

/**
 * Detail-page header: borrower name → status badge → ActionBar.
 */
export function DetailHeader({
  detail,
  onTransitionSuccess,
  lastUpdatedAt,
  isRefreshing = false,
  onRefresh,
}: DetailHeaderProps) {
  const { session } = useSession();
  // M19 — every check below consults the session's full role set so a
  // multi-role user (e.g. OPS_USER+PRODUCT_ADMIN) gets the union of their
  // affordances rather than the primary role's slice.
  const roles = session?.user.roles ?? [];
  const hasRole = (r: Role) => roles.includes(r);
  // `ForeclosureQuotePanel` owns the foreclosure transition when it renders, so
  // the two must be decided together — see `hiddenTargetStatuses` below.
  const showsForeclosurePanel = hasRole("SYSTEM_ADMIN");
  const mutation = useTransitionStatus(detail.application.id);
  const disbursementMutation = useInitiateDisbursement(detail.application.id);
  const overrideMutation = useManualStatusOverride(detail.application.id);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [overrideBusy, setOverrideBusy] = useState(false);
  const [overrideError, setOverrideError] = useState<string | null>(null);
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

  // Hide the Override action for sources the backend is known to block
  // (approval/invalid/servicing/closed/foreclosed, or a submitted/parked
  // disbursement account). No new eligibility endpoint — the server stays
  // authoritative for races; this only removes a known-dead affordance.
  const overrideHiddenForSource = isManualOverrideSourceBlocked(
    detail.application.status,
    detail.account?.accountStatus ?? null,
  );
  const canOfferOverride = hasRole("SYSTEM_ADMIN") && !overrideHiddenForSource;
  // Never offer the status the application is already in.
  const overrideTargets = manualOverrideTargetsFor(detail.application.status);
  // H28 — lifecycle actions are only offered for a recognized status. An
  // unknown status cannot be mapped onto the transition matrix, so the bar
  // is replaced by an honest note instead of actions computed from a
  // fabricated fallback status.
  const knownStatus = isLoanApplicationStatus(detail.application.status)
    ? detail.application.status
    : null;

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

  const handleOverrideConfirm = async ({
    toStatus,
    reason,
    reasonCode,
    idempotencyKey,
  }: {
    toStatus: TransitionStatusInput["to"];
    reason: string;
    reasonCode: string;
    idempotencyKey: string;
  }) => {
    setOverrideBusy(true);
    setOverrideError(null);
    try {
      // Key comes from the dialog session (reused on identical retry,
      // fresh for a changed payload) — never the failed standard key.
      const result = await overrideMutation.mutateAsync({
        to: toStatus,
        reason,
        reasonCode,
        idempotencyKey,
      });
      // Show the resulting status from the server response.
      toast.success(
        `Application moved to ${formatLoanStatusLabel(result.application.status)} (override).`,
      );
      setOverrideOpen(false);
      onTransitionSuccess?.();
    } catch (err) {
      const detailMsg = mapApiErrorMessage(err);
      toast.error(`Could not override status: ${detailMsg}`);
      // Keep the dialog open with the failure where the admin is looking.
      setOverrideError(detailMsg);
    } finally {
      setOverrideBusy(false);
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
            {onRefresh ? (
              <div data-slot="detail-refresh" className="flex items-center gap-2">
                {lastUpdatedAt ? (
                  <span className="text-foreground-muted text-xs">
                    Updated{" "}
                    <AbsoluteRelativeTime
                      iso={new Date(lastUpdatedAt).toISOString()}
                      variant="relative"
                    />
                  </span>
                ) : null}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={onRefresh}
                  disabled={isRefreshing}
                >
                  <RefreshCw
                    aria-hidden="true"
                    className={cn(
                      "size-4",
                      isRefreshing && "animate-spin motion-reduce:animate-none",
                    )}
                  />
                  <span>Refresh</span>
                </Button>
              </div>
            ) : null}
            <StatusBadge
              status={detail.application.status}
              delinquency={detail.accountDelinquency}
            />
          </div>
        }
      />

      {hasRole("OPS_USER") && !hasRole("SYSTEM_ADMIN") ? (
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
      ) : roles.length > 0 ? (
        <>
          {knownStatus ? (
            <ActionBar
              currentStatus={knownStatus}
              roles={roles}
              applicationId={detail.application.id}
              gates={{
                docsComplete: detail.docsComplete,
                scheduleValid: detail.scheduleValid,
              }}
              onConfirm={handleConfirm}
              // Only relocate foreclosure when the panel that owns it is actually
              // rendered below; otherwise the bar would hide an action nothing else
              // offers.
              hiddenTargetStatuses={showsForeclosurePanel ? FORECLOSURE_OWNED_BY_PANEL : undefined}
            />
          ) : (
            <p data-slot="unknown-status-actions-note" className="text-foreground-muted text-sm">
              Lifecycle actions are unavailable: the application status is not recognized.
            </p>
          )}
          {canOfferOverride ? (
            <div data-slot="admin-override-bar" className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  setOverrideError(null);
                  setOverrideOpen(true);
                }}
                data-action-id="MANUAL_STATUS_OVERRIDE"
              >
                <AlertTriangle aria-hidden="true" className="size-4" />
                <span>Override status</span>
              </Button>
              <ManualStatusOverrideDialog
                open={overrideOpen}
                onOpenChange={(next) => {
                  if (overrideBusy) return;
                  setOverrideOpen(next);
                  if (!next) setOverrideError(null);
                }}
                allowedTargets={overrideTargets}
                onConfirm={handleOverrideConfirm}
                loading={overrideBusy}
                errorMessage={overrideError}
              />
            </div>
          ) : null}
        </>
      ) : null}

      {showsForeclosurePanel ? (
        <ForeclosureQuotePanel detail={detail} onExecuted={onTransitionSuccess} />
      ) : null}
    </div>
  );
}
