import { useEffect, useMemo, useRef } from "react";
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
  const schema = useMemo(
    () => makeRepaymentPostSchema(outstandingAmount),
    [outstandingAmount],
  );

  const initialPostedAt = defaultPostedAt ?? toLocalInputValue(new Date());

  const form = useForm<RepaymentPostValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      amount: outstandingAmount,
      postedAt: initialPostedAt,
      mode: defaultMode,
    },
    mode: "onSubmit",
  });

  const amountRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) {
      form.reset({
        amount: outstandingAmount,
        postedAt: initialPostedAt,
        mode: defaultMode,
      });
      return;
    }
    // Refresh defaults when the dialog re-opens against a different row.
    form.reset({
      amount: outstandingAmount,
      postedAt: initialPostedAt,
      mode: defaultMode,
    });
    // Move focus to the amount input after Radix mounts the dialog. Using a
    // ref + setTimeout(0) keeps jsx-a11y/no-autofocus happy.
    const id = window.setTimeout(() => amountRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
    // We intentionally re-run on every open, plus when outstanding/mode change
    // so the form picks up a fresh outstanding total without a parent remount.
  }, [open, outstandingAmount, defaultMode, initialPostedAt, form]);

  const handleSubmit = async (values: RepaymentPostValues) => {
    // The `<input type="datetime-local">` value omits seconds + timezone
    // ("2026-05-17T15:29"), but the mock router's `Iso8601` schema requires
    // a full ISO 8601 string with offset. Normalise via the local Date.
    const postedAtIso = new Date(values.postedAt).toISOString();
    await onConfirm({
      amount: values.amount,
      postedAt: postedAtIso,
      mode: values.mode,
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
            Post the full outstanding amount{subjectSuffix}. Per BR-13, partial
            payments are not accepted — the amount must equal{" "}
            <strong>{formatINR(outstandingAmount, { decimals: 2 })}</strong>.
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
                    type="number"
                    inputMode="decimal"
                    step="0.01"
                    min={0}
                    data-tabular="true"
                    {...field}
                    value={Number.isFinite(field.value) ? field.value : ""}
                    onChange={(event) => {
                      const raw = event.target.value;
                      field.onChange(raw === "" ? Number.NaN : Number(raw));
                    }}
                    ref={(el) => {
                      field.ref(el);
                      amountRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>
                  Must equal the outstanding amount of the next installment.
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
                  <Input
                    type="datetime-local"
                    {...field}
                  />
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
            name="mode"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Mode</FormLabel>
                <Select value={field.value} onValueChange={field.onChange}>
                  <FormControl>
                    <SelectTrigger
                      aria-label="Repayment mode"
                      className="w-full"
                    >
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
