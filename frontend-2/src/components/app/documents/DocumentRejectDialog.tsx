import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertTriangle } from "lucide-react";
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
import { DOCUMENT_KIND_LABELS, type DocumentKind } from "@/schemas/document";
import { DocumentRejectSchema, type DocumentRejectValues } from "./schema";

export interface DocumentRejectDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Used in dialog copy to disambiguate which document is being rejected. */
  kind?: DocumentKind;
  /** Optional file name for additional disambiguation. */
  fileName?: string | null;
  onConfirm: (args: { rejectionReason: string; idempotencyKey: string }) => Promise<void> | void;
  /** Disables submit + shows a working state. */
  loading?: boolean;
}

/**
 * Dialog for rejecting an uploaded document. RHF + Zod (`schema.ts`) so the
 * fast-refresh "only export components" rule stays clean. Mints a fresh
 * idempotency key (BR-5) on every confirm and forwards it to the consumer.
 */
export function DocumentRejectDialog({
  open,
  onOpenChange,
  kind,
  fileName,
  onConfirm,
  loading = false,
}: DocumentRejectDialogProps) {
  const form = useForm<DocumentRejectValues>({
    resolver: zodResolver(DocumentRejectSchema),
    defaultValues: { rejectionReason: "" },
    mode: "onSubmit",
  });
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    if (!open) {
      form.reset({ rejectionReason: "" });
      return;
    }
    // Move focus to the reason field on open. Doing it via a ref + effect
    // keeps jsx-a11y/no-autofocus happy and lets Radix mount the dialog
    // before we steal focus.
    const id = window.setTimeout(() => textareaRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [open, form]);

  const handleSubmit = async (values: DocumentRejectValues) => {
    await onConfirm({
      rejectionReason: values.rejectionReason,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  const kindLabel = kind ? DOCUMENT_KIND_LABELS[kind] : "document";
  const fileSuffix = fileName ? ` (${fileName})` : "";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <AlertTriangle className="text-danger h-5 w-5" aria-hidden="true" />
            <DialogTitle>Reject document</DialogTitle>
          </div>
          <DialogDescription>
            Rejecting this {kindLabel}
            {fileSuffix} is recorded in the document access audit log with your name, timestamp,
            and the reason you provide. The borrower will be asked to re-upload.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="rejectionReason"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Reason for rejection</FormLabel>
                <FormControl>
                  <Textarea
                    rows={3}
                    maxLength={500}
                    placeholder="e.g. Document is not legible — please re-upload a clearer scan."
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
            <Button type="submit" variant="destructive" disabled={loading}>
              {loading ? "Rejecting…" : "Reject"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
