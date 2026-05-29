import { AlertTriangle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { cn } from "@/lib/utils";

export interface DisbursementGateBannerProps {
  /** BR-3: every required-for-disbursement document is uploaded. */
  docsComplete: boolean;
  /** BR-10: a valid repayment schedule exists for the loan. */
  scheduleValid: boolean;
  className?: string;
}

/**
 * Inline alert that surfaces the BR-3/BR-10 gates blocking a disbursement.
 *
 * The authoritative precondition logic lives in `src/lib/lifecycle.ts`
 * (the `requireDisbursementDocs` and `requireScheduleValid` chains). This
 * banner is a visual mirror of those gates so operators understand WHY the
 * "Initiate disbursement" action is disabled.
 *
 * Returns null when both gates pass — we never render an empty banner.
 */
export function DisbursementGateBanner({
  docsComplete,
  scheduleValid,
  className,
}: DisbursementGateBannerProps) {
  if (docsComplete && scheduleValid) {
    return null;
  }

  return (
    <Alert variant="destructive" className={cn(className)}>
      <AlertTriangle aria-hidden="true" />
      <AlertTitle>Disbursement blocked</AlertTitle>
      <AlertDescription>
        <ul className="list-disc space-y-1 pl-5">
          {!docsComplete ? <li>Required documents not yet uploaded (BR-3)</li> : null}
          {!scheduleValid ? <li>Repayment schedule not generated (BR-10)</li> : null}
        </ul>
      </AlertDescription>
    </Alert>
  );
}
