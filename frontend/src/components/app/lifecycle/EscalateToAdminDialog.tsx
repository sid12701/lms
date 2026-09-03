import { useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { AlertTriangle } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
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
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { ConfirmDialogFooter } from "@/components/app/forms/ConfirmDialogFooter";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { AlertSubjectType } from "@/schemas/alert";
import {
  ESCALATION_MESSAGE_MAX,
  ESCALATION_TITLE_MAX,
  EscalationFormSchema,
  type EscalationFormValues,
} from "./escalationSchema";

export interface EscalateToAdminDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  subjectType: AlertSubjectType;
  subjectId: string | null;
  /** Optional headline override; defaults to "Escalate to admin". */
  title?: string;
  /** Optional context line above the form. */
  description?: string;
  onConfirm: (args: { title: string; message: string; idempotencyKey: string }) => Promise<void>;
  /** Disables submit while the network call is in-flight. */
  loading?: boolean;
}

/**
 * OPS_USER escalation surface (Gap #16). Ops surfaces a stuck loan to
 * SYSTEM_ADMIN by emitting an `OPS_USER_ESCALATION` alert. Title + message
 * are required; both are recorded on the alert audit row and visible in
 * the admin alerts inbox.
 *
 * The form runs through `FormShell` rather than local `useState` because a
 * failed submit has to both announce (the shell's live-region summary) and
 * move focus to the first invalid control. Hand-rolling those two guarantees
 * here would have duplicated the shell without earning anything back.
 */
export function EscalateToAdminDialog({
  open,
  onOpenChange,
  subjectType: _subjectType,
  subjectId: _subjectId,
  title,
  description,
  onConfirm,
  loading = false,
}: EscalateToAdminDialogProps) {
  const form = useForm<EscalationFormValues>({
    resolver: zodResolver(EscalationFormSchema),
    defaultValues: { title: "", message: "" },
    mode: "onSubmit",
  });
  const titleRef = useRef<HTMLInputElement | null>(null);

  useFlushOnClose(open, () => form.reset({ title: "", message: "" }));
  useFocusOnOpen(open, titleRef);

  const handleSubmit = async (values: EscalationFormValues) => {
    await onConfirm({
      title: values.title,
      message: values.message,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <AlertTriangle className="text-warning h-5 w-5" aria-hidden="true" />
            <DialogTitle>{title ?? "Escalate to admin"}</DialogTitle>
          </div>
          <DialogDescription>
            {description ??
              "Create a high-severity alert for SYSTEM_ADMIN. Use this when an automated flow is stuck and needs human intervention."}
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="title"
            render={({ field }) => (
              <FormItem required>
                <FormLabel>Title</FormLabel>
                <FormControl>
                  <Input
                    maxLength={ESCALATION_TITLE_MAX}
                    placeholder="e.g. Disbursement adapter has failed 6 times"
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      titleRef.current = el;
                    }}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="message"
            render={({ field }) => (
              <FormItem required>
                <FormLabel>Message</FormLabel>
                <FormControl>
                  <Textarea
                    rows={4}
                    maxLength={ESCALATION_MESSAGE_MAX}
                    placeholder="Describe what's stuck and what you've tried."
                    {...field}
                  />
                </FormControl>
                <FormDescription>
                  Up to {ESCALATION_MESSAGE_MAX} characters. Visible to admins in the alerts inbox.
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <ConfirmDialogFooter
            loading={loading}
            onCancel={() => onOpenChange(false)}
            submitLabel="Send escalation"
            loadingLabel="Sending…"
          />
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
