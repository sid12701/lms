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

export interface ActionBarProps {
  currentStatus: LoanStatus;
  role: Role;
  /** Externally-evaluated business gates surfaced as "disabled with reason". */
  gates?: TransitionGates;
  onConfirm: (args: {
    action: LifecycleAction;
    reason: string | null;
    idempotencyKey: string;
  }) => Promise<void> | void;
  className?: string;
}

const TONE_BUTTON: Record<LifecycleAction["tone"], string> = {
  default: "",
  destructive: "",
  // No "success" Button variant ships, so we layer brand-success utility
  // classes on top of the default variant for the approve tone.
  approve:
    "bg-success text-success-foreground hover:bg-success/90 focus-visible:ring-success/30",
};

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
  gates,
  onConfirm,
  className,
}: ActionBarProps) {
  const [activeAction, setActiveAction] = useState<LifecycleAction | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [statusMessage, setStatusMessage] = useState("");

  const items = useMemo(() => {
    const all = actionsFor(currentStatus);
    return all.map((action) => ({
      action,
      disabledReason: resolveDisabledReason(action, currentStatus, role, gates),
    }));
  }, [currentStatus, role, gates]);

  const handleClick = (action: LifecycleAction) => {
    setActiveAction(action);
    setOpen(true);
  };

  const handleConfirm = async (args: {
    action: LifecycleAction;
    reason: string | null;
    idempotencyKey: string;
  }) => {
    setBusy(true);
    try {
      await onConfirm(args);
      setStatusMessage(`Action "${args.action.label}" completed.`);
      setOpen(false);
      setActiveAction(null);
    } catch (e) {
      const detail = e instanceof Error ? e.message : "Please try again.";
      setStatusMessage(`Action "${args.action.label}" failed: ${detail}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      data-slot="action-bar"
      className={cn("flex flex-col gap-2", className)}
    >
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Loan actions">
        {items.length === 0 ? (
          <p className="text-foreground-muted text-sm" data-slot="action-bar-empty">
            No actions available from this status.
          </p>
        ) : (
          items.map(({ action, disabledReason }) => {
            const variant: "default" | "destructive" | "outline" =
              action.tone === "destructive"
                ? "destructive"
                : action.tone === "approve"
                  ? "default"
                  : "outline";
            return (
              <TransitionDisabledTooltip
                key={action.id}
                disabledReason={disabledReason}
              >
                <Button
                  type="button"
                  variant={variant}
                  className={cn(TONE_BUTTON[action.tone])}
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

      <span
        data-slot="action-bar-status"
        aria-live="polite"
        aria-atomic="true"
        className="sr-only"
      >
        {statusMessage}
      </span>

      <TransitionConfirmDialog
        open={open}
        onOpenChange={(next) => {
          if (busy) return;
          setOpen(next);
          if (!next) setActiveAction(null);
        }}
        action={activeAction}
        onConfirm={handleConfirm}
        loading={busy}
      />
    </div>
  );
}
