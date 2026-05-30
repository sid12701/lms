/**
 * shadcn-Dialog + RHF + Zod prompt to edit an existing LSP.
 *
 * `code` is shown as a read-only field (immutable post-create).
 * Only `name` and `status` flow through to the PATCH handler.
 */
import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Building2 } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { LspStatus } from "@/schemas/lsp";
import type { LspRow } from "../types";
import { EditLspFormSchema, type EditLspFormValues } from "./editLspSchema";

export interface LspEditConfirmArgs {
  id: string;
  name: string;
  status: LspStatus;
  idempotencyKey: string;
}

export interface LspEditDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Current row being edited; `null` while the dialog is closed. */
  lsp: LspRow | null;
  onConfirm: (args: LspEditConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

const STATUS_OPTIONS: readonly { value: LspStatus; label: string }[] = [
  { value: "ACTIVE", label: "Active" },
  { value: "SUSPENDED", label: "Suspended" },
  { value: "INACTIVE", label: "Inactive" },
];

export function LspEditDialog({
  open,
  onOpenChange,
  lsp,
  onConfirm,
  loading = false,
  errorMessage = null,
}: LspEditDialogProps) {
  const form = useForm<EditLspFormValues>({
    resolver: zodResolver(EditLspFormSchema),
    defaultValues: { name: "", status: "ACTIVE" },
    mode: "onSubmit",
  });

  const nameRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open || !lsp) {
      form.reset({ name: "", status: "ACTIVE" });
      return;
    }
    form.reset({ name: lsp.name, status: lsp.status });
    const id = window.setTimeout(() => nameRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [open, lsp, form]);

  const handleSubmit = async (values: EditLspFormValues) => {
    if (!lsp) return;
    await onConfirm({
      id: lsp.id,
      name: values.name.trim(),
      status: values.status,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Building2 className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>Edit LSP</DialogTitle>
          </div>
          <DialogDescription>
            Update the display name or operational status. The code is immutable.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1">
            <FormLabel htmlFor="lsp-edit-code">Code</FormLabel>
            <Input
              id="lsp-edit-code"
              value={lsp?.code ?? ""}
              readOnly
              disabled
              data-slot="lsp-edit-code-readonly"
            />
          </div>

          <FormField
            control={form.control}
            name="name"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Display name</FormLabel>
                <FormControl>
                  <Input
                    maxLength={120}
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      nameRef.current = el;
                    }}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="status"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Status</FormLabel>
                <FormControl>
                  <Select value={field.value} onValueChange={(v) => field.onChange(v as LspStatus)}>
                    <SelectTrigger data-slot="lsp-edit-status-trigger">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {STATUS_OPTIONS.map((o) => (
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
            <Button type="submit" disabled={loading || !lsp}>
              {loading ? "Saving…" : "Save changes"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
