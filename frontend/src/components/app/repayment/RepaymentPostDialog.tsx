import { useCallback, useMemo, useRef, useState } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useSyncOnOpen } from "@/lib/hooks/use-sync-on-open";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Receipt } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
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
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import { formatINR } from "@/lib/format";
import {
  REPAYMENT_POST_MODES,
  REPAYMENT_POST_MODE_LABELS,
  makeRepaymentPostSchema,
  type RepaymentPostMode,
  type RepaymentPostValues,
} from "./schema";

export interface RepaymentPostConfirmArgs {
  amount: number;
  postedAt: string;
  mode: RepaymentPostMode;
  reference: string | null;
  /** Fresh BR-5 idempotency key minted at submit time. */
  idempotencyKey: string;
}

export interface RepaymentPostDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Outstanding amount on the installment being repaid. BR-13: amount must equal this exactly. */
  outstandingAmount: number;
  /** Optional human-readable label for the installment, e.g. "Installment 7". */
  installmentLabel?: string;
  /** Default value for the posting timestamp (defaults to "now" on each open). */
  defaultPostedAt?: string;
  /** Default channel — falls back to BANK_TRANSFER. */
  defaultMode?: RepaymentPostMode;
  onConfirm: (args: RepaymentPostConfirmArgs) => Promise<void> | void;
  /** Disables submit + flips the button to a "Posting…" label. */
  loading?: boolean;
}

/** Format a Date for an `<input type="datetime-local">` value (no timezone). */
function toLocalInputValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}`;
}

/**
 * shadcn-Dialog + RHF + Zod prompt for posting a single repayment against the
 * next-due installment. BR-13 is enforced by the schema: the amount must
 * equal the outstanding amount exactly.
 *
 * Submit signature is `{ amount, postedAt, mode, idempotencyKey }`. The key
 * is minted via `newIdempotencyKey()` at submit time per BR-5.
 */
export function RepaymentPostDialog({
  open,
  onOpenChange,
  outstandingAmount,
  installmentLabel,
  defaultPostedAt,
  defaultMode = "BANK_TRANSFER",
  onConfirm,
  loading = false,
}: RepaymentPostDialogProps) {
  const schema = useMemo(() => makeRepaymentPostSchema(outstandingAmount), [outstandingAmount]);

  const initialPostedAt = defaultPostedAt ?? toLocalInputValue(new Date());

  const form = useForm<RepaymentPostValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      amount: outstandingAmount,
      postedAt: initialPostedAt,
      mode: defaultMode,
      reference: "",
    },
    mode: "onSubmit",
  });

  const amountRef = useRef<HTMLInputElement | null>(null);

  const resetForm = useCallback(() => {
    form.reset({
      amount: outstandingAmount,
      postedAt: initialPostedAt,
      mode: defaultMode,
      reference: "",
    });
  }, [form, outstandingAmount, initialPostedAt, defaultMode]);

  useFlushOnClose(open, resetForm);
  useSyncOnOpen(open, resetForm);

  const formDepsKey = `${outstandingAmount}:${initialPostedAt}:${defaultMode}`;
  const [prevFormDepsKey, setPrevFormDepsKey] = useState(formDepsKey);
  if (open && formDepsKey !== prevFormDepsKey) {
    setPrevFormDepsKey(formDepsKey);
    resetForm();
  }

  useFocusOnOpen(open, amountRef);

  const handleSubmit = async (values: RepaymentPostValues) => {
    // The `<input type="datetime-local">` value omits seconds + timezone
    // ("2026-05-17T15:29"), but the backend's `Iso8601` schema requires
    // a full ISO 8601 string with offset. Normalise via the local Date.
    const postedAtIso = new Date(values.postedAt).toISOString();
    await onConfirm({
      amount: values.amount,
      postedAt: postedAtIso,
      mode: values.mode,
      reference: values.reference?.trim() ? values.reference.trim() : null,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  const subjectSuffix = installmentLabel ? ` for ${installmentLabel}` : "";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Receipt className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>Record repayment</DialogTitle>
          </div>
          <DialogDescription>
            Post the full outstanding amount{subjectSuffix}. Partial payments are not accepted — the
            amount must equal <strong>{formatINR(outstandingAmount, { decimals: 2 })}</strong>.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="amount"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Amount (INR)</FormLabel>
                <FormControl>
                  <Input
                    type="text"
                    readOnly
                    data-tabular="true"
                    className="bg-muted"
                    value={formatINR(outstandingAmount, { decimals: 2 })}
                    ref={(el) => {
                      field.ref(el);
                      amountRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>
                  Fixed to the installment due amount (exact match required).
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="postedAt"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Posted at</FormLabel>
                <FormControl>
                  <Input type="datetime-local" {...field} />
                </FormControl>
                <FormDescription>
                  When the payment was actually received from the borrower.
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="reference"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Bank reference (optional)</FormLabel>
                <FormControl>
                  <Input placeholder="UTR / cheque no. / UPI tx id" maxLength={128} {...field} />
                </FormControl>
                <FormDescription>
                  Reconciliation reference — can be added later when the bank statement arrives.
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="mode"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Mode</FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl>
                    <SelectTrigger aria-label="Repayment mode" className="w-full">
                      <SelectValue placeholder="Select a mode" />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {REPAYMENT_POST_MODES.map((mode) => (
                      <SelectItem key={mode} value={mode}>
                        {REPAYMENT_POST_MODE_LABELS[mode]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={loading}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={loading}>
              {loading ? "Posting…" : "Post repayment"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
