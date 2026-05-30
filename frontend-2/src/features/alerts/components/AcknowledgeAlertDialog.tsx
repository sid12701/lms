import { useRef } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { BellRing } from "lucide-react";
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
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import { AcknowledgeAlertSchema, type AcknowledgeAlertValues } from "./schema";

export interface AcknowledgeAlertConfirmArgs {
  note: string | null;
  /** Fresh BR-5 idempotency key minted at submit time. */
  idempotencyKey: string;
}

export interface AcknowledgeAlertDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Short title of the alert being acknowledged, surfaced in the description. */
  alertTitle: string;
  onConfirm: (args: AcknowledgeAlertConfirmArgs) => Promise<void> | void;
  /** Disables submit + swaps button label. */
  loading?: boolean;
  /** Optional submit error to surface inline (e.g. business-rule failure). */
  errorMessage?: string | null;
}

/**
 * shadcn-Dialog + RHF + Zod prompt to acknowledge an operational alert.
 * The note field is optional; submit mints a BR-5 idempotency key.
 */
export function AcknowledgeAlertDialog({
  open,
  onOpenChange,
  alertTitle,
  onConfirm,
  loading = false,
  errorMessage = null,
}: AcknowledgeAlertDialogProps) {
  const form = useForm<AcknowledgeAlertValues>({
    resolver: zodResolver(AcknowledgeAlertSchema),
    defaultValues: { note: "" },
    mode: "onSubmit",
  });

  const noteRef = useRef<HTMLTextAreaElement | null>(null);

  useFlushOnClose(open, () => form.reset({ note: "" }));
  useFocusOnOpen(open, noteRef);

  const handleSubmit = async (values: AcknowledgeAlertValues) => {
    const note = values.note?.trim() ? values.note.trim() : null;
    await onConfirm({
      note,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <BellRing className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>Acknowledge alert</DialogTitle>
          </div>
          <DialogDescription>
            Confirm that this alert has been triaged: <strong>{alertTitle}</strong>. An optional
            note is recorded against the acknowledgement.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="note"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Acknowledgement note</FormLabel>
                <FormControl>
                  <Textarea
                    rows={4}
                    maxLength={500}
                    placeholder="What action was taken? (optional)"
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      noteRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>
                  Up to 500 characters. Stored on the alert and shown in the inbox.
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          {errorMessage ? (
            <div
              role="alert"
              className="border-danger/30 bg-danger/5 text-danger rounded-md border p-3 text-sm"
            >
              {errorMessage}
            </div>
          ) : null}

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
              {loading ? "Acknowledging…" : "Acknowledge"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
