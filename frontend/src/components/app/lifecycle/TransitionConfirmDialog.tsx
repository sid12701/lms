import { useEffect, useMemo, useRef, useState, type RefObject } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertTriangle, ArrowRight, CheckCircle2, type LucideIcon } from "lucide-react";
import { Dialog, DialogContent } from "@/components/ui/dialog";
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
import { Textarea } from "@/components/ui/textarea";
import { FormShell } from "@/components/app/forms/FormShell";
import { ConfirmDialogFooter } from "@/components/app/forms/ConfirmDialogFooter";
import { FormDialogHeader } from "@/components/app/forms/FormDialogHeader";
import {
  DisbursementPreviewSummary,
  type DisbursementPreviewData,
} from "@/components/app/disbursement/DisbursementPreviewSummary";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { LifecycleAction, LifecycleActionTone } from "./actions";
import { LIFECYCLE_REASON_CODES } from "./reason-codes";
import { lifecycleDialogSchema, type LifecycleDialogValues } from "./schema";

export interface TransitionConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  action: LifecycleAction | null;
  /** When set and the action targets DISBURSED, loads the ops disbursement preview. */
  applicationId?: string;
  /** Optional headline that overrides the auto-generated title. */
  title?: string;
  /** Optional explainer line above the reason field. */
  description?: string;
  onConfirm: (args: {
    action: LifecycleAction;
    /** Trimmed reason; null when the action did not require one and user left it blank. */
    reason: string | null;
    /** Structured reason code; null unless the action requires one. */
    reasonCode: string | null;
    /** Fresh BR-5 idempotency key minted at submit time. */
    idempotencyKey: string;
  }) => Promise<void> | void;
  /** Disables submit + flips the button to a "Working…" state. */
  loading?: boolean;
  /**
   * Failure detail from the most recent submit. Rendered inside the dialog —
   * page-level live regions are invisible behind the modal overlay.
   */
  errorMessage?: string | null;
  /** Loads the disbursement preview for DISBURSED actions. */
  loadDisbursementPreview?: (applicationId: string) => Promise<DisbursementPreviewData>;
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

interface PreviewState {
  data: DisbursementPreviewData | null;
  loading: boolean;
  error: string | null;
}

const EMPTY_PREVIEW: PreviewState = { data: null, loading: false, error: null };

interface PreviewRequest {
  applicationId: string;
  load: (applicationId: string) => Promise<DisbursementPreviewData>;
}

interface SettledPreview {
  request: PreviewRequest;
  data: DisbursementPreviewData | null;
  error: string | null;
}

function useDisbursementPreview({
  enabled,
  applicationId,
  load,
}: {
  enabled: boolean;
  applicationId?: string;
  load?: (applicationId: string) => Promise<DisbursementPreviewData>;
}): PreviewState {
  const request = useMemo<PreviewRequest | null>(
    () => (enabled && applicationId && load ? { applicationId, load } : null),
    [enabled, applicationId, load],
  );
  const [settled, setSettled] = useState<SettledPreview | null>(null);

  useEffect(() => {
    if (!request) return;

    let cancelled = false;
    void request
      .load(request.applicationId)
      .then((data) => {
        if (!cancelled) setSettled({ request, data, error: null });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        const message =
          error instanceof Error ? error.message : "Could not load disbursement preview.";
        setSettled({ request, data: null, error: message });
      });

    return () => {
      cancelled = true;
    };
  }, [request]);

  if (!request) return EMPTY_PREVIEW;
  if (settled?.request !== request) {
    return { data: null, loading: true, error: null };
  }
  return { data: settled.data, loading: false, error: settled.error };
}

function DisbursementPreview({ state }: { state: PreviewState }) {
  return (
    <div className="flex flex-col gap-2">
      {state.loading ? (
        <p
          data-slot="disbursement-preview-loading"
          className="text-foreground-muted text-sm"
          aria-live="polite"
        >
          Loading disbursement summary…
        </p>
      ) : null}
      {state.error ? (
        <p
          role="alert"
          data-slot="disbursement-preview-error"
          className="text-danger text-xs/relaxed"
        >
          {state.error}
        </p>
      ) : null}
      {state.data ? <DisbursementPreviewSummary preview={state.data} /> : null}
    </div>
  );
}

function ReasonCodeField({ form }: { form: ReturnType<typeof useLifecycleDialogForm> }) {
  return (
    <FormField
      control={form.control}
      name="reasonCode"
      render={({ field }) => (
        <FormItem>
          <FormLabel>Reason</FormLabel>
          <Select value={field.value ?? ""} onValueChange={field.onChange}>
            <FormControl>
              <SelectTrigger
                aria-label="Reason code"
                data-slot="transition-reason-code"
                className="w-full"
              >
                <SelectValue placeholder="Select a reason" />
              </SelectTrigger>
            </FormControl>
            <SelectContent>
              {LIFECYCLE_REASON_CODES.map((code) => (
                <SelectItem key={code.value} value={code.value}>
                  {code.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <FormDescription>Recorded with the transition and shared with the LSP.</FormDescription>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

function useLifecycleDialogForm(requiresReason: boolean, requiresReasonCode: boolean) {
  const schema = useMemo(
    () => lifecycleDialogSchema({ requiresReason, requiresReasonCode }),
    [requiresReason, requiresReasonCode],
  );
  return useForm<LifecycleDialogValues>({
    resolver: zodResolver(schema),
    defaultValues: { reason: "", reasonCode: "" },
    mode: "onSubmit",
  });
}

function ReasonDetailsField({
  form,
  requiresReason,
  requiresReasonCode,
  textareaRef,
}: {
  form: ReturnType<typeof useLifecycleDialogForm>;
  requiresReason: boolean;
  requiresReasonCode: boolean;
  textareaRef: RefObject<HTMLTextAreaElement | null>;
}) {
  return (
    <FormField
      control={form.control}
      name="reason"
      render={({ field }) => (
        <FormItem>
          <FormLabel>
            {requiresReasonCode ? "Details" : requiresReason ? "Reason" : "Reason (optional)"}
          </FormLabel>
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
              ref={(element) => {
                field.ref(element);
                textareaRef.current = element;
              }}
            />
          </FormControl>
          <FormDescription>Up to 1000 characters. Visible to auditors.</FormDescription>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

/**
 * Shadcn-Dialog + RHF + Zod confirm prompt for any lifecycle transition.
 *
 * The schema is selected at runtime from `action.requiresReason` /
 * `action.requiresReasonCode`:
 *   - reason required → trim().min(1).max(1000), else optional
 *   - reason code required (REJECTED / DISBURSEMENT_RETRY targets) → a
 *     structured code from `LIFECYCLE_REASON_CODES` must be selected
 *
 * On submit we mint a fresh idempotency key (BR-5) and forward it alongside
 * the trimmed reason, the reason code, and the originating action.
 */
export function TransitionConfirmDialog({
  open,
  onOpenChange,
  action,
  applicationId,
  title,
  description,
  onConfirm,
  loading = false,
  errorMessage = null,
  loadDisbursementPreview,
}: TransitionConfirmDialogProps) {
  const requiresReason = action?.requiresReason ?? false;
  const requiresReasonCode = action?.requiresReasonCode ?? false;
  const isDisbursementAction = action?.toStatus === "DISBURSED";
  const form = useLifecycleDialogForm(requiresReason, requiresReasonCode);

  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useFlushOnClose(open, () => form.reset({ reason: "", reasonCode: "" }));
  useEffect(() => {
    if (open) form.reset({ reason: "", reasonCode: "" });
  }, [action?.id, open, form]);
  useFocusOnOpen(open, textareaRef);

  const preview = useDisbursementPreview({
    enabled: open && isDisbursementAction,
    applicationId,
    load: loadDisbursementPreview,
  });

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
    (isDisbursementAction
      ? "Review the disbursement summary below. The net amount and beneficiary must match before you confirm."
      : requiresReason
        ? "Provide a reason for this change. It is recorded in the application audit log alongside your name and the time."
        : "This will move the application to a new lifecycle status. The change is recorded in the application audit log.");

  const confirmBlockedByPreview =
    isDisbursementAction &&
    Boolean(applicationId && loadDisbursementPreview) &&
    (preview.loading || preview.error !== null || preview.data === null);

  const handleSubmit = async (values: LifecycleDialogValues) => {
    const trimmed = (values.reason ?? "").trim();
    const reasonCode = (values.reasonCode ?? "").trim();
    await onConfirm({
      action,
      reason: trimmed.length > 0 ? trimmed : null,
      reasonCode: reasonCode.length > 0 ? reasonCode : null,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  const confirmVariant: "default" | "destructive" =
    tone === "destructive" ? "destructive" : "default";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <FormDialogHeader
          icon={Icon}
          iconClassName={iconClass}
          title={headline}
          description={explainer}
        />

        {isDisbursementAction && applicationId && loadDisbursementPreview ? (
          <DisbursementPreview state={preview} />
        ) : null}

        <FormShell form={form} onSubmit={handleSubmit}>
          {requiresReasonCode ? <ReasonCodeField form={form} /> : null}
          <ReasonDetailsField
            form={form}
            requiresReason={requiresReason}
            requiresReasonCode={requiresReasonCode}
            textareaRef={textareaRef}
          />

          {errorMessage ? (
            <p
              role="alert"
              data-slot="transition-dialog-error"
              className="text-danger text-xs/relaxed"
            >
              {errorMessage}
            </p>
          ) : null}

          <ConfirmDialogFooter
            loading={loading}
            submitDisabled={confirmBlockedByPreview}
            onCancel={() => onOpenChange(false)}
            submitLabel={action.label}
            submitVariant={confirmVariant}
          />
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
