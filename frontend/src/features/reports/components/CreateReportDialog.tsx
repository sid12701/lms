import { useRef } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { FileBarChart2 } from "lucide-react";
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
import { DatePickerField } from "@/components/app/data/DatePickerField";
import { ConfirmDialogFooter } from "@/components/app/forms/ConfirmDialogFooter";
import { FormDialogErrorAlert } from "@/components/app/forms/FormDialogErrorAlert";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { CreateReportRequestInput } from "../types";
import { CreateReportRequestFormSchema, type CreateReportRequestFormValues } from "./schema";

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

  useFlushOnClose(open, () => form.reset(defaults));
  useFocusOnOpen(open, lspIdRef);

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
            <FileBarChart2 className="text-primary-tinted h-5 w-5" aria-hidden="true" />
            <DialogTitle>Generate portfolio MIS report</DialogTitle>
          </div>
          <DialogDescription>
            Queue a CSV export of the portfolio. Reports complete in the background — refresh the
            requests list to track progress.
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
                <FormDescription>Optional. Leave blank for a cross-LSP export.</FormDescription>
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
                    <DatePickerField
                      value={field.value}
                      onChange={field.onChange}
                      ariaLabel="Disbursed from"
                      className="w-full [&_button]:h-9"
                    />
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
                    <DatePickerField
                      value={field.value}
                      onChange={field.onChange}
                      ariaLabel="Disbursed to"
                      className="w-full [&_button]:h-9"
                    />
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
                  <Input type="email" placeholder="you@example.com" {...field} />
                </FormControl>
                <FormDescription>
                  Optional — receive an alert when the report completes.
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormDialogErrorAlert message={errorMessage} />

          <ConfirmDialogFooter
            loading={loading}
            onCancel={() => onOpenChange(false)}
            submitLabel="Queue report"
            loadingLabel="Queuing…"
          />
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
