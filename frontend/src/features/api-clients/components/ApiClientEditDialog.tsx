/**
 * Edit-API-client dialog.
 *
 * Renames a client and/or toggles its status (enable/disable) via
 * `PUT /api-clients/{id}`. Disabling a client blocks new token issuance
 * immediately. The dialog mints the BR-5 idempotency key internally and
 * surfaces the non-secret metadata (client id + last rotation) read-only.
 *
 * IP allow-lists are managed per LSP, not per client, so they are not editable
 * here (see the LSPs admin page).
 */
import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { KeyRound } from "lucide-react";
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { FormShell } from "@/components/app/forms/FormShell";
import { FormDialogErrorAlert } from "@/components/app/forms/FormDialogErrorAlert";
import { ConfirmDialogFooter } from "@/components/app/forms/ConfirmDialogFooter";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { formatDateTime } from "@/lib/format";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { ApiClientStatus } from "@/schemas/user";
import { EditApiClientSchema, type EditApiClientValues } from "./schema";
import type { ApiClientRow } from "../types";

export interface ApiClientEditDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The client being edited; null while the dialog is closed. */
  client: ApiClientRow | null;
  /**
   * Persist the edit. The dialog mints the BR-5 idempotency key internally so
   * callers don't have to.
   */
  onSave: (input: {
    name: string;
    status: ApiClientStatus;
    idempotencyKey: string;
  }) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

export function ApiClientEditDialog({
  open,
  onOpenChange,
  client,
  onSave,
  loading = false,
  errorMessage = null,
}: ApiClientEditDialogProps) {
  const form = useForm<EditApiClientValues>({
    resolver: zodResolver(EditApiClientSchema),
    defaultValues: { name: "", status: "ACTIVE" },
    mode: "onSubmit",
  });

  const nameRef = useRef<HTMLInputElement | null>(null);

  // Re-seed the form whenever the target client changes so each open reflects
  // the current row rather than stale values from a previous edit.
  useEffect(() => {
    if (client) {
      form.reset({ name: client.name, status: client.status });
    }
  }, [client, form]);

  useFocusOnOpen(open, nameRef);

  const handleSubmit = async (values: EditApiClientValues) => {
    await onSave({
      name: values.name,
      status: values.status,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <KeyRound aria-hidden="true" className="text-progress h-5 w-5" />
            <DialogTitle>Edit API client</DialogTitle>
          </div>
          <DialogDescription>
            Rename the client or enable/disable it. Disabling immediately blocks new token issuance.
          </DialogDescription>
        </DialogHeader>

        {client ? (
          <FormShell form={form} onSubmit={handleSubmit}>
            <div data-slot="api-client-edit-meta" className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div className="flex flex-col gap-1">
                <span className="text-muted-foreground text-xs tracking-wide uppercase">
                  Client ID
                </span>
                <span className="font-mono text-sm tabular-nums">{client.clientId}</span>
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-muted-foreground text-xs tracking-wide uppercase">
                  Last rotated
                </span>
                <span data-slot="api-client-edit-rotated" className="text-sm tabular-nums">
                  {client.lastRotatedAt ? formatDateTime(client.lastRotatedAt) : "Never rotated"}
                </span>
              </div>
            </div>

            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      ref={(el) => {
                        field.ref(el);
                        nameRef.current = el;
                      }}
                      maxLength={120}
                    />
                  </FormControl>
                  <FormDescription>Human-readable label shown in the admin list.</FormDescription>
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
                    <Select value={field.value} onValueChange={field.onChange}>
                      <SelectTrigger
                        size="sm"
                        aria-label="Status"
                        data-slot="api-client-edit-status"
                      >
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="ACTIVE">Active</SelectItem>
                        <SelectItem value="DISABLED">Disabled</SelectItem>
                      </SelectContent>
                    </Select>
                  </FormControl>
                  <FormDescription>
                    Disabled clients cannot obtain new access tokens.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormDialogErrorAlert message={errorMessage} />

            <ConfirmDialogFooter
              loading={loading}
              onCancel={() => onOpenChange(false)}
              submitLabel="Save changes"
              loadingLabel="Saving…"
            />
          </FormShell>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}
