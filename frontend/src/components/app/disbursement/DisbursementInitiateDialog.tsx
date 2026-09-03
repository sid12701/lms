import { useRef } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Banknote } from "lucide-react";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import {
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Textarea } from "@/components/ui/textarea";
import { FormShell } from "@/components/app/forms/FormShell";
import { ConfirmDialogFooter } from "@/components/app/forms/ConfirmDialogFooter";
import { FormDialogHeader } from "@/components/app/forms/FormDialogHeader";
import { newIdempotencyKey } from "@/lib/idempotency";
import { maskAccount } from "@/lib/format";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { DisbursementInitiateSchema, type DisbursementInitiateValues } from "./schema";

export interface DisbursementTarget {
  /** Raw account number — masked for display via `maskAccount`. */
  accountNumber: string;
  ifsc: string;
  beneficiaryName: string;
  bankName?: string;
}

export interface DisbursementInitiateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Read-only target block shown in the dialog body. */
  target: DisbursementTarget;
  onConfirm: (args: {
    /** Trimmed operator note; null when blank. */
    note: string | null;
    /** Fresh BR-5 idempotency key minted at submit time. */
    idempotencyKey: string;
  }) => Promise<void> | void;
  /** Disables submit + flips the button to a "Initiating…" state. */
  loading?: boolean;
}

/**
 * Confirmation dialog for initiating disbursement from APPROVED_PENDING_DISBURSAL
 * transition. Mirrors the TransitionConfirmDialog UX but adds a read-only target
 * block so the operator visually verifies bank details before initiating.
 *
 * BR-5: a fresh idempotency key is minted on every submit and forwarded to the
 * caller — the page-level handler passes it to the backend.
 */
export function DisbursementInitiateDialog({
  open,
  onOpenChange,
  target,
  onConfirm,
  loading = false,
}: DisbursementInitiateDialogProps) {
  const form = useForm<DisbursementInitiateValues>({
    resolver: zodResolver(DisbursementInitiateSchema),
    defaultValues: { note: "" },
    mode: "onSubmit",
  });
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useFlushOnClose(open, () => form.reset({ note: "" }));
  useFocusOnOpen(open, textareaRef);

  const handleSubmit = async (values: DisbursementInitiateValues) => {
    const trimmed = (values.note ?? "").trim();
    await onConfirm({
      note: trimmed.length > 0 ? trimmed : null,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <FormDialogHeader
          icon={Banknote}
          iconClassName="text-foreground-muted"
          title="Initiate disbursement"
          description="Confirm the disbursement target. A disbursement request is queued and the change is recorded in the application audit log alongside your name and the time."
        />

        <div
          data-slot="disbursement-target"
          className="border-border bg-card-muted/40 rounded-container border p-3"
          aria-label="Disbursement target"
        >
          <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
            <div className="flex flex-col gap-0.5">
              <dt className="text-foreground-muted text-eyebrow uppercase">Beneficiary</dt>
              <dd className="text-foreground font-medium">{target.beneficiaryName}</dd>
            </div>
            {target.bankName ? (
              <div className="flex flex-col gap-0.5">
                <dt className="text-foreground-muted text-eyebrow uppercase">Bank</dt>
                <dd className="text-foreground font-medium">{target.bankName}</dd>
              </div>
            ) : null}
            <div className="flex flex-col gap-0.5">
              <dt className="text-foreground-muted text-eyebrow uppercase">Account number</dt>
              <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
                {maskAccount(target.accountNumber)}
              </dd>
            </div>
            <div className="flex flex-col gap-0.5">
              <dt className="text-foreground-muted text-eyebrow uppercase">IFSC</dt>
              <dd className="text-foreground font-medium" {...TABULAR_ATTR}>
                {target.ifsc}
              </dd>
            </div>
          </dl>
        </div>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="note"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Note (optional)</FormLabel>
                <FormControl>
                  <Textarea
                    rows={3}
                    maxLength={500}
                    placeholder="Optional context for the audit log."
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      textareaRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>Up to 500 characters. Visible to auditors.</FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <ConfirmDialogFooter
            loading={loading}
            onCancel={() => onOpenChange(false)}
            submitLabel="Initiate disbursement"
            loadingLabel="Initiating…"
          />
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
