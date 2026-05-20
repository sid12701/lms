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
import { RotateSecretSchema, type RotateSecretValues } from "./schema";

export interface RotateSecretDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Optional client label woven into the dialog copy. */
  clientLabel?: string;
  /**
   * Called with the validated reason and a fresh idempotency key (BR-5).
   * The parent is responsible for issuing the rotate mutation and surfacing
   * the new secret via `ApiSecretReveal`.
   */
  onConfirm: (values: { reason: string; idempotencyKey: string }) => Promise<void> | void;
  /** Disables submit + cancel and shows a working state on the confirm button. */
  loading?: boolean;
}

/**
 * Confirmation dialog for irreversible secret rotation. Rotating invalidates
 * the current secret immediately — any integration still using it begins
 * failing on the very next request — so we require the operator to provide
 * a written reason for audit before submitting.
 */
export function RotateSecretDialog({
  open,
  onOpenChange,
  clientLabel,
  onConfirm,
  loading = false,
}: RotateSecretDialogProps) {
  const form = useForm<RotateSecretValues>({
    resolver: zodResolver(RotateSecretSchema),
    defaultValues: { reason: "" },
    mode: "onSubmit",
  });
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    if (!open) {
      form.reset({ reason: "" });
      return;
    }
    // Move focus to the reason field on open via ref + setTimeout instead of
    // `autoFocus`. Lets Radix finish mounting before we steal focus and keeps
    // jsx-a11y/no-autofocus happy.
    const id = window.setTimeout(() => textareaRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [open, form]);

  const handleSubmit = async (values: RotateSecretValues) => {
    await onConfirm({ reason: values.reason, idempotencyKey: newIdempotencyKey() });
  };

  const subjectSuffix = clientLabel ? ` for ${clientLabel}` : "";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <ShieldAlert className="text-danger h-5 w-5" aria-hidden="true" />
            <DialogTitle>Rotate API client secret{subjectSuffix}</DialogTitle>
          </div>
          <DialogDescription>
            <strong className="text-danger">
              Rotating invalidates the current secret immediately. Any integration still using it
              will fail.
            </strong>{" "}
            The new secret will be shown to you once and never again.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="reason"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Reason for rotation</FormLabel>
                <FormControl>
                  <Textarea
                    rows={3}
                    maxLength={500}
                    placeholder="e.g. Suspected secret leakage in partner build pipeline."
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      textareaRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>Up to 500 characters. Recorded in the audit log.</FormDescription>
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
              {loading ? "Rotating…" : "Rotate secret"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
