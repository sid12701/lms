/**
 * ProductCreateDialog — RHF + Zod create form, sibling schema in `./schema.ts`.
 *
 * The form mints a BR-5 idempotency key at submit time via
 * `newIdempotencyKey()`. RHF mirrors superRefine errors (principalMax <
 * principalMin, tenureMaxMonths < tenureMinMonths) so inline messages appear
 * before the mock router ever sees the payload.
 */
import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Layers } from "lucide-react";
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
import type { CreateProductInput } from "../types";
import { CreateProductFormSchema, type CreateProductFormValues } from "./schema";
import type { LspChoice } from "../hooks/useLspChoices";
import { LspMultiSelect } from "./LspMultiSelect";

export interface ProductCreateConfirmArgs {
  input: CreateProductInput;
}

export interface ProductCreateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lspChoices: readonly LspChoice[];
  onConfirm: (args: ProductCreateConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

const DEFAULTS: CreateProductFormValues = {
  code: "",
  name: "",
  principalMin: 0,
  principalMax: 0,
  interestRatePct: 0,
  processingFeePct: 0,
  tenureMinMonths: 1,
  tenureMaxMonths: 12,
  lspIds: [],
};

export function ProductCreateDialog({
  open,
  onOpenChange,
  lspChoices,
  onConfirm,
  loading = false,
  errorMessage = null,
}: ProductCreateDialogProps) {
  const form = useForm<CreateProductFormValues>({
    resolver: zodResolver(CreateProductFormSchema),
    defaultValues: DEFAULTS,
    mode: "onSubmit",
  });

  const codeRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) {
      form.reset(DEFAULTS);
      return;
    }
    const id = window.setTimeout(() => codeRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [open, form]);

  const handleSubmit = async (values: CreateProductFormValues) => {
    await onConfirm({
      input: {
        code: values.code.trim().toUpperCase(),
        name: values.name.trim(),
        principalMin: values.principalMin,
        principalMax: values.principalMax,
        interestRatePct: values.interestRatePct,
        processingFeePct: values.processingFeePct,
        tenureMinMonths: values.tenureMinMonths,
        tenureMaxMonths: values.tenureMaxMonths,
        lspIds: values.lspIds,
        idempotencyKey: newIdempotencyKey(),
      },
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Layers className="text-primary h-5 w-5" aria-hidden="true" />
            <DialogTitle>New loan product</DialogTitle>
          </div>
          <DialogDescription>
            Configure a new loan product. The code must be unique across the catalog. Mapping to
            LSPs can be edited later.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <FormField
              control={form.control}
              name="code"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Code</FormLabel>
                  <FormControl>
                    <Input
                      placeholder="PL-STD"
                      {...field}
                      ref={(el) => {
                        field.ref(el);
                        codeRef.current = el;
                      }}
                    />
                  </FormControl>
                  <FormDescription>
                    Short identifier. Letters, digits, and hyphens; 2–24 chars.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input placeholder="Personal Loan — Standard" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <FormField
              control={form.control}
              name="principalMin"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Min principal (INR)</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      inputMode="numeric"
                      min={1}
                      step={1000}
                      value={Number.isFinite(field.value) ? field.value : 0}
                      onChange={(e) => field.onChange(Number(e.target.value))}
                      onBlur={field.onBlur}
                      name={field.name}
                      ref={field.ref}
                      data-slot="products-principal-min"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="principalMax"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Max principal (INR)</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      inputMode="numeric"
                      min={1}
                      step={1000}
                      value={Number.isFinite(field.value) ? field.value : 0}
                      onChange={(e) => field.onChange(Number(e.target.value))}
                      onBlur={field.onBlur}
                      name={field.name}
                      ref={field.ref}
                      data-slot="products-principal-max"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <FormField
              control={form.control}
              name="interestRatePct"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Interest rate (%)</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      inputMode="decimal"
                      min={0}
                      step={0.25}
                      value={Number.isFinite(field.value) ? field.value : 0}
                      onChange={(e) => field.onChange(Number(e.target.value))}
                      onBlur={field.onBlur}
                      name={field.name}
                      ref={field.ref}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="processingFeePct"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Processing fee (%)</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      inputMode="decimal"
                      min={0}
                      step={0.25}
                      value={Number.isFinite(field.value) ? field.value : 0}
                      onChange={(e) => field.onChange(Number(e.target.value))}
                      onBlur={field.onBlur}
                      name={field.name}
                      ref={field.ref}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <FormField
              control={form.control}
              name="tenureMinMonths"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Min tenure (months)</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={360}
                      step={1}
                      value={Number.isFinite(field.value) ? field.value : 1}
                      onChange={(e) => field.onChange(Number(e.target.value))}
                      onBlur={field.onBlur}
                      name={field.name}
                      ref={field.ref}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="tenureMaxMonths"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Max tenure (months)</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={360}
                      step={1}
                      value={Number.isFinite(field.value) ? field.value : 12}
                      onChange={(e) => field.onChange(Number(e.target.value))}
                      onBlur={field.onBlur}
                      name={field.name}
                      ref={field.ref}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

          <FormField
            control={form.control}
            name="lspIds"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Available to LSPs</FormLabel>
                <FormControl>
                  <LspMultiSelect
                    choices={lspChoices}
                    selected={field.value ?? []}
                    onChange={(next) => field.onChange(next)}
                  />
                </FormControl>
                <FormDescription>
                  Pick the LSPs this product is offered to. You can change this later from the row
                  actions menu.
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
              {loading ? "Creating…" : "Create product"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
