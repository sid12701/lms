import { useRef } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertTriangle } from "lucide-react";
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
import { ForeclosureRequestSchema, type ForeclosureRequestValues } from "./schema";

export interface ForeclosureRequestDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /**
   * Submit handler. Receives the trimmed reason, the trimmed note (or null
   * when the user left it blank), and a fresh BR-5 idempotency key minted
   * inside the dialog at submit time.
   */
  onConfirm: (args: {
    reason: string;
    note: string | null;
    idempotencyKey: string;
  }) => Promise<void> | void;
  /** Disables form + flips submit button to "Submitting…". */
  loading?: boolean;
}

/**
 * Reason-required dialog for the FORECLOSURE_TRIGGER permission gate.
 *
 * Tied to BR-7-style audit posture: the reason is what gets logged on the
 * application audit row when the foreclosure transition fires, so it must
 * be filled in before submit unlocks. The dialog mints a fresh idempotency
 * key at submit time (BR-5) and forwards it to `onConfirm`.
 */
export function ForeclosureRequestDialog({
  open,
  onOpenChange,
  onConfirm,
  loading = false,
}: ForeclosureRequestDialogProps) {
  const form = useForm<ForeclosureRequestValues>({
    resolver: zodResolver(ForeclosureRequestSchema),
    defaultValues: { reason: "", note: "" },
    mode: "onSubmit",
  });

  const reasonRef = useRef<HTMLTextAreaElement | null>(null);

  useFlushOnClose(open, () => form.reset({ reason: "", note: "" }));
  useFocusOnOpen(open, reasonRef);

  const handleSubmit = async (values: ForeclosureRequestValues) => {
    const trimmedReason = values.reason.trim();
    const trimmedNote = (values.note ?? "").trim();
    await onConfirm({
      reason: trimmedReason,
      note: trimmedNote.length > 0 ? trimmedNote : null,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <FormDialogHeader
          icon={AlertTriangle}
          iconClassName="text-warning"
          title="Request foreclosure"
          description="Provide a reason for requesting foreclosure. The reason is recorded in the application audit log alongside your name, the time, and the foreclosure quote."
        />

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="reason"
            render={({ field }) => (
              <FormItem required>
                <FormLabel>Reason</FormLabel>
                <FormControl>
                  <Textarea
                    rows={3}
                    maxLength={500}
                    placeholder="e.g. Borrower requested early closure in writing."
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      reasonRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>Up to 500 characters. Visible to auditors.</FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="note"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Note (optional)</FormLabel>
                <FormControl>
                  <Textarea
                    rows={2}
                    maxLength={500}
                    placeholder="Optional internal context for ops review."
                    {...field}
                    value={field.value ?? ""}
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
            submitLabel="Request foreclosure"
            loadingLabel="Submitting…"
          />
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
