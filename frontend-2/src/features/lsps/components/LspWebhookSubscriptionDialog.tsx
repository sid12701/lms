/**
 * shadcn-Dialog + RHF + Zod prompt to configure a webhook subscription for
 * an LSP.
 *
 *   - enabled toggle (switch-styled Button, role=switch)
 *   - https-only endpoint URL (zod-validated)
 *   - signing secret ≥ 32 chars (textarea so long secrets are readable)
 *   - multi-select event types (chip buttons, aria-pressed)
 *
 * Submit mints a BR-5 idempotency key. Pre-fills from `initialSubscription`
 * when an existing subscription is present (or empty defaults otherwise).
 */
import { useMemo, useRef } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useSyncOnOpen } from "@/lib/hooks/use-sync-on-open";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Check, Webhook } from "lucide-react";
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
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { FormShell } from "@/components/app/forms/FormShell";
import { cn } from "@/lib/utils";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { LspWebhookSubscription, WebhookEventType } from "@/schemas/lsp";
import {
  ALL_WEBHOOK_EVENT_TYPES,
  WebhookSubscriptionFormSchema,
  type WebhookSubscriptionFormValues,
} from "./webhookSubscriptionSchema";

export interface LspWebhookSubscriptionConfirmArgs {
  id: string;
  enabled: boolean;
  endpointUrl: string;
  signingSecret: string;
  eventTypes: WebhookEventType[];
  idempotencyKey: string;
}

export interface LspWebhookSubscriptionDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** LSP id being edited. */
  lspId: string | null;
  /** Friendly title shown in the header (`{code} — {name}`). */
  lspLabel: string;
  /** Current subscription, or `null` if none exists yet. */
  initialSubscription: LspWebhookSubscription | null;
  onConfirm: (args: LspWebhookSubscriptionConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

const EVENT_LABELS: Record<WebhookEventType, string> = {
  "loan.created": "Loan created",
  "loan.status.changed": "Status changed",
  "loan.disbursement.completed": "Disbursement completed",
  "loan.repayment.posted": "Repayment posted",
  "loan.foreclosed": "Loan foreclosed",
};

const DEFAULT_VALUES: WebhookSubscriptionFormValues = {
  enabled: false,
  endpointUrl: "",
  signingSecret: "",
  eventTypes: [],
};

export function LspWebhookSubscriptionDialog({
  open,
  onOpenChange,
  lspId,
  lspLabel,
  initialSubscription,
  onConfirm,
  loading = false,
  errorMessage = null,
}: LspWebhookSubscriptionDialogProps) {
  const form = useForm<WebhookSubscriptionFormValues>({
    resolver: zodResolver(WebhookSubscriptionFormSchema),
    defaultValues: DEFAULT_VALUES,
    mode: "onSubmit",
  });

  const urlRef = useRef<HTMLInputElement | null>(null);

  const initialValues = useMemo<WebhookSubscriptionFormValues>(() => {
    if (!initialSubscription) return DEFAULT_VALUES;
    return {
      enabled: initialSubscription.enabled,
      endpointUrl: initialSubscription.endpointUrl,
      signingSecret: initialSubscription.signingSecret,
      eventTypes: [...initialSubscription.eventTypes],
    };
  }, [initialSubscription]);

  useFlushOnClose(open, () => form.reset(DEFAULT_VALUES));
  useSyncOnOpen(open, () => form.reset(initialValues));
  useFocusOnOpen(open, urlRef);

  const handleSubmit = async (values: WebhookSubscriptionFormValues) => {
    if (!lspId) return;
    await onConfirm({
      id: lspId,
      enabled: values.enabled,
      endpointUrl: values.endpointUrl.trim(),
      signingSecret: values.signingSecret,
      eventTypes: values.eventTypes,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Webhook className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>Webhook subscription</DialogTitle>
          </div>
          <DialogDescription>
            Configure where Bhawana posts lifecycle events for <strong>{lspLabel}</strong>. HMAC
            signatures use the signing secret you provide.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="enabled"
            render={({ field }) => (
              <FormItem className="border-border bg-surface-muted/40 flex items-center justify-between gap-4 rounded-md border p-3">
                <div className="flex flex-col gap-0.5">
                  <FormLabel className="m-0">Deliveries enabled</FormLabel>
                  <FormDescription>
                    Disable to keep configuration but pause outbound POSTs.
                  </FormDescription>
                </div>
                <FormControl>
                  <Button
                    type="button"
                    variant={field.value ? "default" : "outline"}
                    size="sm"
                    role="switch"
                    aria-checked={field.value}
                    data-slot="webhook-enabled-toggle"
                    onClick={() => field.onChange(!field.value)}
                  >
                    {field.value ? "Enabled" : "Disabled"}
                  </Button>
                </FormControl>
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="endpointUrl"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Endpoint URL</FormLabel>
                <FormControl>
                  <Input
                    placeholder="https://hooks.example.com/lms/events"
                    inputMode="url"
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      urlRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>HTTPS only.</FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="signingSecret"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Signing secret</FormLabel>
                <FormControl>
                  <Textarea
                    rows={3}
                    maxLength={256}
                    placeholder="At least 32 characters"
                    {...field}
                  />
                </FormControl>
                <FormDescription>
                  32-256 characters. Used to HMAC-sign every outbound payload.
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="eventTypes"
            render={({ field }) => {
              const selected = new Set(field.value);
              const toggle = (val: WebhookEventType) => {
                if (selected.has(val)) {
                  field.onChange(field.value.filter((v) => v !== val));
                } else {
                  field.onChange([...field.value, val]);
                }
              };
              return (
                <FormItem>
                  <FormLabel>Event types</FormLabel>
                  <FormControl>
                    <ul
                      data-slot="webhook-event-chips"
                      className="flex flex-wrap gap-1.5"
                      role="group"
                      aria-label="Webhook event types"
                    >
                      {ALL_WEBHOOK_EVENT_TYPES.map((evt) => {
                        const isOn = selected.has(evt);
                        return (
                          <li key={evt}>
                            <Button
                              type="button"
                              variant={isOn ? "default" : "outline"}
                              size="sm"
                              aria-pressed={isOn}
                              data-slot={`webhook-event-${evt}`}
                              onClick={() => toggle(evt)}
                              className="gap-1"
                            >
                              {isOn ? <Check aria-hidden="true" className="size-3" /> : null}
                              <span>{EVENT_LABELS[evt]}</span>
                            </Button>
                          </li>
                        );
                      })}
                    </ul>
                  </FormControl>
                  <FormDescription>
                    Pick at least one. Bhawana fans out lifecycle events to every selected type.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          {errorMessage ? (
            <div
              role="alert"
              className={cn(
                "border-danger/30 bg-danger/5 text-danger rounded-md border p-3 text-sm",
              )}
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
            <Button type="submit" disabled={loading || !lspId}>
              {loading ? "Saving…" : "Save subscription"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
