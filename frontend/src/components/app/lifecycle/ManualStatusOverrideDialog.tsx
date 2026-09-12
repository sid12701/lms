import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ShieldAlert } from "lucide-react";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import {
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { FormShell } from "@/components/app/forms/FormShell";
import { ConfirmDialogFooter } from "@/components/app/forms/ConfirmDialogFooter";
import { FormDialogHeader } from "@/components/app/forms/FormDialogHeader";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { newIdempotencyKey } from "@/lib/idempotency";
import { ALLOWED_MANUAL_OVERRIDE_TARGETS } from "@/features/loan-applications/api-detail";
import { LIFECYCLE_REASON_CODES } from "./reason-codes";
import type { LoanStatus } from "@/types";

const overrideSchema = z.object({
  toStatus: z.string().min(1, "Select a target status."),
  reasonCode: z.string().min(1, "Select a reason."),
  reason: z
    .string()
    .trim()
    .min(1, "Explanation is required.")
    .max(500, "Explanation must be 500 characters or fewer."),
});

export interface ManualOverrideConfirmArgs {
  toStatus: LoanStatus;
  reason: string;
  reasonCode: string;
  /**
   * Idempotency key for this deliberate command. Retrying the IDENTICAL
   * payload after an uncertain response reuses the key so the server
   * dedupes; a changed payload or a new dialog session mints a new one.
   * Never the prior standard action's key (the dialog mints its own).
   */
  idempotencyKey: string;
}

export interface ManualStatusOverrideDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Targets offered. Defaults to the backend-permitted non-financial set. */
  allowedTargets?: readonly LoanStatus[];
  onConfirm: (args: ManualOverrideConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

/**
 * Separate admin override dialog. Only rendered for SYSTEM_ADMIN by the
 * host (DetailHeader), which also hides it for backend-blocked source states
 * and passes targets excluding the current status; the server remains
 * authoritative for source, financial (DISBURSED/CLOSED/FORECLOSED) and
 * in-flight guards. Cancel/dismiss invokes only `onOpenChange(false)` — no
 * network traffic.
 */
export function ManualStatusOverrideDialog({
  open,
  onOpenChange,
  allowedTargets = [...ALLOWED_MANUAL_OVERRIDE_TARGETS],
  onConfirm,
  loading = false,
  errorMessage = null,
}: ManualStatusOverrideDialogProps) {
  const form = useForm<z.infer<typeof overrideSchema>>({
    resolver: zodResolver(overrideSchema),
    defaultValues: { toStatus: "", reasonCode: "", reason: "" },
  });

  // Idempotency-key lifecycle for this dialog session: a new session starts
  // with no key (so it can never equal the prior standard action's key).
  // The first submit mints one; retrying the identical payload after an
  // uncertain response reuses it so the server dedupes instead of
  // double-submitting. Any changed payload mints a new key.
  const attemptKeyRef = useRef<string | null>(null);
  const lastPayloadRef = useRef<string | null>(null);
  const resetAttemptState = () => {
    attemptKeyRef.current = null;
    lastPayloadRef.current = null;
  };

  useFlushOnClose(open, () => {
    form.reset({ toStatus: "", reasonCode: "", reason: "" });
    resetAttemptState();
  });
  useEffect(() => {
    if (open) {
      form.reset({ toStatus: "", reasonCode: "", reason: "" });
      resetAttemptState();
    }
  }, [open, form]);

  const handleSubmit = async (values: z.infer<typeof overrideSchema>) => {
    const reason = values.reason.trim();
    const signature = `${values.toStatus}|${values.reasonCode}|${reason}`;
    const idempotencyKey =
      attemptKeyRef.current != null && lastPayloadRef.current === signature
        ? attemptKeyRef.current
        : newIdempotencyKey();
    attemptKeyRef.current = idempotencyKey;
    lastPayloadRef.current = signature;
    try {
      await onConfirm({
        toStatus: values.toStatus as LoanStatus,
        reason,
        reasonCode: values.reasonCode,
        idempotencyKey,
      });
    } catch {
      // The host surfaces the failure via `errorMessage` and keeps the dialog
      // open; the key is retained so an identical retry dedupes.
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <FormDialogHeader
          icon={ShieldAlert}
          title="Override status"
          description="Move this application to a different status outside the standard flow. The new status and your reason are recorded in the audit log."
        />
        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="toStatus"
            render={({ field }) => (
              <FormItem required>
                <FormLabel>Target status</FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl>
                    <SelectTrigger
                      aria-label="Target status"
                      data-slot="override-target-status"
                      className="w-full"
                    >
                      <SelectValue placeholder="Select a target status" />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {allowedTargets.map((status) => (
                      <SelectItem key={status} value={status}>
                        {status}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="reasonCode"
            render={({ field }) => (
              <FormItem required>
                <FormLabel>Reason</FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl>
                    <SelectTrigger
                      aria-label="Reason code"
                      data-slot="override-reason-code"
                      className="w-full"
                    >
                      <SelectValue placeholder="Select a reason" />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {LIFECYCLE_REASON_CODES.map((code) => (
                      <SelectItem key={code.value} value={code.value}>
                        {code.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormDescription>
                  Recorded with the override and shared with the LSP.
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="reason"
            render={({ field }) => (
              <FormItem required>
                <FormLabel>Explanation</FormLabel>
                <FormControl>
                  <Textarea
                    rows={3}
                    maxLength={500}
                    placeholder="e.g. Verified by phone that the borrower re-submitted KYC; moving back for re-review."
                    {...field}
                  />
                </FormControl>
                <FormDescription>Up to 500 characters. Visible to auditors.</FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />
          {errorMessage ? (
            <p
              role="alert"
              data-slot="override-dialog-error"
              className="text-danger text-xs/relaxed"
            >
              {errorMessage}
            </p>
          ) : null}
          <ConfirmDialogFooter
            loading={loading}
            onCancel={() => onOpenChange(false)}
            submitLabel="Confirm override"
            submitDataSlot="override-confirm"
          />
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
