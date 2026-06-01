/**
 * Operator flow for `PUT …/lsps/{id}/status` (disable / reactivate kill chain).
 */
import { useRef } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useSyncOnOpen } from "@/lib/hooks/use-sync-on-open";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertTriangle, ShieldOff } from "lucide-react";
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
import { Textarea } from "@/components/ui/textarea";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { LspOperationalStatus, LspStatusChangeReason } from "@/schemas/lsp";
import type { LspRow } from "../types";
import {
  LSP_OPERATIONAL_STATUS_OPTIONS,
  LSP_STATUS_CHANGE_REASON_OPTIONS,
  lspStatusChangeActionLabel,
} from "./lspStatusLabels";
import { LspStatusChangeFormSchema, type LspStatusChangeFormValues } from "./lspStatusChangeSchema";

export interface LspStatusChangeConfirmArgs {
  id: string;
  status: LspOperationalStatus;
  reason: LspStatusChangeReason;
  note: string;
  idempotencyKey: string;
}

export interface LspStatusChangeDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lsp: LspRow | null;
  onConfirm: (args: LspStatusChangeConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

function toOperationalStatus(status: LspRow["status"]): LspOperationalStatus {
  return status === "INACTIVE" ? "INACTIVE" : "ACTIVE";
}

export function LspStatusChangeDialog({
  open,
  onOpenChange,
  lsp,
  onConfirm,
  loading = false,
  errorMessage = null,
}: LspStatusChangeDialogProps) {
  const form = useForm<LspStatusChangeFormValues>({
    resolver: zodResolver(LspStatusChangeFormSchema),
    defaultValues: { status: "ACTIVE", reason: "OPERATIONAL", note: "" },
    mode: "onSubmit",
  });

  const noteRef = useRef<HTMLTextAreaElement | null>(null);
  const currentOperational = lsp ? toOperationalStatus(lsp.status) : "ACTIVE";
  const targetStatus = form.watch("status");
  const isDisable = targetStatus === "INACTIVE" && currentOperational !== "INACTIVE";
  const isReactivate = targetStatus === "ACTIVE" && currentOperational === "INACTIVE";
  const unchanged = targetStatus === currentOperational;

  useFlushOnClose(open, () => form.reset({ status: "ACTIVE", reason: "OPERATIONAL", note: "" }));
  useSyncOnOpen(open, () => {
    if (lsp) {
      form.reset({
        status: toOperationalStatus(lsp.status),
        reason: "OPERATIONAL",
        note: "",
      });
    }
  });
  useFocusOnOpen(open, noteRef);

  const handleSubmit = async (values: LspStatusChangeFormValues) => {
    if (!lsp) return;
    await onConfirm({
      id: lsp.id,
      status: values.status,
      reason: values.reason,
      note: values.note.trim(),
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <ShieldOff className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>Change LSP status</DialogTitle>
          </div>
          <DialogDescription>
            {lsp ? (
              <>
                Update operational status for{" "}
                <span className="font-mono font-medium">{lsp.code}</span>. Disabling revokes all
                outstanding API tokens and deactivates API clients under this tenant.
              </>
            ) : (
              "Select a target status, reason, and audit note."
            )}
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="status"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Target status</FormLabel>
                <FormControl>
                  <Select
                    value={field.value}
                    onValueChange={(v) => field.onChange(v as LspOperationalStatus)}
                  >
                    <SelectTrigger data-slot="lsp-status-change-target">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {LSP_OPERATIONAL_STATUS_OPTIONS.map((o) => (
                        <SelectItem key={o.value} value={o.value}>
                          {o.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          {isDisable ? (
            <div
              role="alert"
              className="border-danger/30 bg-danger/5 text-danger flex gap-2 rounded-md border p-3 text-sm"
              data-slot="lsp-disable-warning"
            >
              <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <div className="flex flex-col gap-1">
                <p className="font-medium">This disables the LSP immediately</p>
                <ul className="text-danger/90 list-disc pl-4">
                  <li>All LSP and API-client JWTs are revoked (token version bump).</li>
                  <li>Every API client under this LSP is set to inactive.</li>
                  <li>A high-severity ops alert is raised for operators.</li>
                </ul>
              </div>
            </div>
          ) : null}

          {isReactivate ? (
            <div
              className="border-warning/30 bg-warning/5 text-foreground rounded-md border p-3 text-sm"
              data-slot="lsp-reactivate-warning"
            >
              Reactivating restores LSP access only. API clients stay inactive until their secrets
              are rotated from the API clients admin surface.
            </div>
          ) : null}

          <FormField
            control={form.control}
            name="reason"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Reason</FormLabel>
                <FormControl>
                  <Select
                    value={field.value}
                    onValueChange={(v) => field.onChange(v as LspStatusChangeReason)}
                  >
                    <SelectTrigger data-slot="lsp-status-change-reason">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {LSP_STATUS_CHANGE_REASON_OPTIONS.map((o) => (
                        <SelectItem key={o.value} value={o.value}>
                          {o.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </FormControl>
                <FormDescription>
                  {
                    LSP_STATUS_CHANGE_REASON_OPTIONS.find((o) => o.value === field.value)
                      ?.description
                  }
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="note"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Audit note</FormLabel>
                <FormControl>
                  <Textarea
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      noteRef.current = el;
                    }}
                    rows={3}
                    maxLength={2000}
                    placeholder="Describe why this status change is being made…"
                    data-slot="lsp-status-change-note"
                  />
                </FormControl>
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
            <Button
              type="submit"
              variant={isDisable ? "destructive" : "default"}
              disabled={loading || !lsp || unchanged}
              data-slot="lsp-status-change-submit"
            >
              {loading ? "Saving…" : lspStatusChangeActionLabel(currentOperational, targetStatus)}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
