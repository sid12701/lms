import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { ShieldAlert } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  FormField,
  FormItem,
  FormLabel,
  FormControl,
  FormDescription,
  FormMessage,
} from "@/components/ui/form";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { PiiFieldName } from "@/schemas/audit";
import { PiiRevealReasonSchema, type PiiRevealReasonValues } from "./schema";

export interface PiiRevealDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Field being revealed — surfaced in the dialog copy. */
  fieldName: PiiFieldName;
  /** Optional subject hint, e.g. borrower name or loan ID. */
  subjectLabel?: string;
  onConfirm: (values: { reason: string; idempotencyKey: string }) => Promise<void> | void;
  /** Disables submit + shows a working state. */
  loading?: boolean;
}

const FIELD_COPY: Record<PiiFieldName, string> = {
  PAN: "PAN",
  AADHAAR: "Aadhaar",
  MOBILE: "mobile number",
  ACCOUNT_NUMBER: "bank account number",
  EMAIL: "email address",
};

export function PiiRevealDialog({
  open,
  onOpenChange,
  fieldName,
  subjectLabel,
  onConfirm,
  loading = false,
}: PiiRevealDialogProps) {
  const form = useForm<PiiRevealReasonValues>({
    resolver: zodResolver(PiiRevealReasonSchema),
    defaultValues: { reason: "" },
    mode: "onSubmit",
  });
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    if (!open) form.reset({ reason: "" });
    else {
      // Move focus to the reason field on open. Doing it via a ref + effect
      // (instead of `autoFocus`) keeps jsx-a11y happy and lets Radix finish
      // mounting the dialog before we steal focus.
      const id = window.setTimeout(() => textareaRef.current?.focus(), 0);
      return () => window.clearTimeout(id);
    }
  }, [open, form]);

  const handleSubmit = async (values: PiiRevealReasonValues) => {
    await onConfirm({ reason: values.reason, idempotencyKey: newIdempotencyKey() });
  };

  const fieldCopy = FIELD_COPY[fieldName];
  const subjectSuffix = subjectLabel ? ` for ${subjectLabel}` : "";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <ShieldAlert className="text-warning h-5 w-5" aria-hidden="true" />
            <DialogTitle>Reveal {fieldCopy}</DialogTitle>
          </div>
          <DialogDescription>
            Revealing this {fieldCopy}{subjectSuffix} is recorded in the audit log with your name,
            timestamp, and the reason you provide. Reveal only when needed for the task at hand.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="reason"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Reason for revealing</FormLabel>
                <FormControl>
                  <Textarea
                    rows={3}
                    maxLength={500}
                    placeholder="e.g. Verifying details for repayment query raised by borrower."
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
              {loading ? "Revealing…" : "Reveal"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
