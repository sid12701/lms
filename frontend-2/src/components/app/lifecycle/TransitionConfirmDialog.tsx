import { useRef, useState } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useSyncOnOpen } from "@/lib/hooks/use-sync-on-open";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertTriangle, ArrowRight, CheckCircle2, type LucideIcon } from "lucide-react";
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
import { cn } from "@/lib/utils";
import type { LifecycleAction, LifecycleActionTone } from "./actions";
import {
  LifecycleReasonOptionalSchema,
  LifecycleReasonRequiredSchema,
  type LifecycleReasonOptionalValues,
} from "./schema";

export interface TransitionConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  action: LifecycleAction | null;
  /** Optional headline that overrides the auto-generated title. */
  title?: string;
  /** Optional explainer line above the reason field. */
  description?: string;
  onConfirm: (args: {
    action: LifecycleAction;
    /** Trimmed reason; null when the action did not require one and user left it blank. */
    reason: string | null;
    /** Fresh BR-5 idempotency key minted at submit time. */
    idempotencyKey: string;
  }) => Promise<void> | void;
  /** Disables submit + flips the button to a "Working…" state. */
  loading?: boolean;
}

const TONE_ICONS: Record<LifecycleActionTone, LucideIcon> = {
  destructive: AlertTriangle,
  approve: CheckCircle2,
  default: ArrowRight,
};

const TONE_ICON_CLASSES: Record<LifecycleActionTone, string> = {
  destructive: "text-danger",
  approve: "text-success",
  default: "text-foreground-muted",
};

/**
 * Shadcn-Dialog + RHF + Zod confirm prompt for any lifecycle transition.
 *
 * The schema is selected at runtime based on `action.requiresReason`:
 *   - required → trim().min(1).max(1000)
 *   - optional → trim().max(1000), nullable
 *
 * On submit we mint a fresh idempotency key (BR-5) and forward it alongside
 * the trimmed reason and the originating action.
 */
export function TransitionConfirmDialog({
  open,
  onOpenChange,
  action,
  title,
  description,
  onConfirm,
  loading = false,
}: TransitionConfirmDialogProps) {
  const requiresReason = action?.requiresReason ?? false;
  const schema = requiresReason ? LifecycleReasonRequiredSchema : LifecycleReasonOptionalSchema;

  const form = useForm<LifecycleReasonOptionalValues>({
    resolver: zodResolver(schema),
    defaultValues: { reason: "" },
    mode: "onSubmit",
  });

  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useFlushOnClose(open, () => form.reset({ reason: "" }));
  useSyncOnOpen(open, () => form.reset({ reason: "" }));
  const [prevActionId, setPrevActionId] = useState(action?.id);
  if (open && action?.id !== prevActionId) {
    setPrevActionId(action?.id);
    form.reset({ reason: "" });
  }
  useFocusOnOpen(open, textareaRef);

  if (!action) {
    return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent />
      </Dialog>
    );
  }

  const tone = action.tone;
  const Icon = TONE_ICONS[tone];
  const iconClass = TONE_ICON_CLASSES[tone];
  const headline = title ?? action.label;
  const explainer =
    description ??
    (requiresReason
      ? "Provide a reason for this change. It is recorded in the application audit log alongside your name and the time."
      : "This will move the application to a new lifecycle status. The change is recorded in the application audit log.");

  const confirmVariant: "default" | "destructive" =
    tone === "destructive" ? "destructive" : "default";

  const handleSubmit = async (values: LifecycleReasonOptionalValues) => {
    const trimmed = (values.reason ?? "").trim();
    await onConfirm({
      action,
      reason: trimmed.length > 0 ? trimmed : null,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Icon className={cn("h-5 w-5", iconClass)} aria-hidden="true" />
            <DialogTitle>{headline}</DialogTitle>
          </div>
          <DialogDescription>{explainer}</DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="reason"
            render={({ field }) => (
              <FormItem>
                <FormLabel>{requiresReason ? "Reason" : "Reason (optional)"}</FormLabel>
                <FormControl>
                  <Textarea
                    rows={3}
                    maxLength={1000}
                    placeholder={
                      requiresReason
                        ? "e.g. Borrower requested cancellation in writing."
                        : "Optional context for the audit log."
                    }
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      textareaRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>Up to 1000 characters. Visible to auditors.</FormDescription>
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
            <Button type="submit" variant={confirmVariant} disabled={loading}>
              {loading ? "Working…" : action.label}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
