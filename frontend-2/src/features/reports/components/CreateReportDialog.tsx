import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { FileBarChart2 } from "lucide-react";
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
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { CreateReportRequestInput } from "../types";
import {
  CreateReportRequestFormSchema,
  type CreateReportRequestFormValues,
} from "./schema";

export interface CreateReportConfirmArgs {
  input: CreateReportRequestInput;
  idempotencyKey: string;
}

export interface CreateReportDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Optional initial values pulled from the page-level filter snapshot. */
  initialValues?: {
    lspId?: string | null;
    dateFrom?: string | null;
    dateTo?: string | null;
  };
  onConfirm: (args: CreateReportConfirmArgs) => Promise<void> | void;
  /** Disables submit + swaps button label. */
  loading?: boolean;
  /** Optional submit error to surface inline. */
  errorMessage?: string | null;
}

/**
 * shadcn-Dialog + RHF + Zod prompt to queue a new portfolio MIS report.
 *
 * All four fields are optional. On submit we mint a BR-5 idempotency key
 * (per the project convention) and call the parent's `onConfirm`.
 */
export function CreateReportDialog({
  open,
  onOpenChange,
  initialValues,
  onConfirm,
  loading = false,
  errorMessage = null,
}: CreateReportDialogProps) {
  const defaults: CreateReportRequestFormValues = {
    lspId: initialValues?.lspId ?? "",
    dateFrom: initialValues?.dateFrom ?? "",
    dateTo: initialValues?.dateTo ?? "",
    notificationEmail: "",
  };

  const form = useForm<CreateReportRequestFormValues>({
    resolver: zodResolver(CreateReportRequestFormSchema),
    defaultValues: defaults,
    mode: "onSubmit",
  });

  const lspIdRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) {
      form.reset(defaults);
      return;
    }
    // Focus the first input after Radix mounts the portaled dialog.
    // useRef + setTimeout(0) keeps jsx-a11y/no-autofocus happy (project rule).
    const id = window.setTimeout(() => lspIdRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- defaults derived from props each open
  }, [open]);

  const handleSubmit = async (values: CreateReportRequestFormValues) => {
    const trim = (v?: string) => (v && v.trim() !== "" ? v.trim() : null);
    const input: CreateReportRequestInput = {
      type: "PORTFOLIO_MIS",
      lspId: trim(values.lspId),
      dateFrom: trim(values.dateFrom),
      dateTo: trim(values.dateTo),
      notificationEmail: trim(values.notificationEmail),
    };
    await onConfirm({ input, idempotencyKey: newIdempotencyKey() });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <FileBarChart2 className="text-primary h-5 w-5" aria-hidden="true" />
            <DialogTitle>Generate portfolio MIS report</DialogTitle>
          </div>
          <DialogDescription>
            Queue a CSV export of the portfolio. Reports complete in the
            background — refresh the requests list to track progress.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="lspId"
            render={({ field }) => (
              <FormItem>
                <FormLabel>LSP id</FormLabel>
                <FormControl>
                  <Input
                    placeholder="All LSPs (paste a UUID to scope)"
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      lspIdRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>
                  Optional. Leave blank for a cross-LSP export.
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <FormField
              control={form.control}
              name="dateFrom"
              render={({ field }) => (
                <FormItem>
            <FormLabel>Disbursed from</FormLabel>
                  <FormControl>
                    <Input type="date" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="dateTo"
              render={({ field }) => (
                <FormItem>
            <FormLabel>Disbursed to</FormLabel>
                  <FormControl>
                    <Input type="date" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

          <FormField
            control={form.control}
            name="notificationEmail"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Notification email</FormLabel>
                <FormControl>
                  <Input
                    type="email"
                    placeholder="you@example.com"
                    {...field}
                  />
                </FormControl>
                <FormDescription>
                  Optional — receive an alert when the report completes.
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
              {loading ? "Queuing…" : "Queue report"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
