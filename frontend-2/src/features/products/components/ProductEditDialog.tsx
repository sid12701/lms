/**
 * ProductEditDialog — RHF + Zod update form, sibling schema in `./schema.ts`.
 *
 * Re-uses the create dialog's numeric/RHF wiring but excludes `code`
 * (immutable) and adds a `status` select. The mock router maps a status-only
 * delta to a STATUS_CHANGED audit event; all other deltas map to UPDATED.
 */
import { useEffect, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Edit2 } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { LoanProduct, UpdateProductInput } from "../types";
import { EditProductFormSchema, type EditProductFormValues } from "./schema";

export interface ProductEditConfirmArgs {
  input: UpdateProductInput;
}

export interface ProductEditDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  product: LoanProduct | null;
  onConfirm: (args: ProductEditConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

function defaultsFor(product: LoanProduct | null): EditProductFormValues {
  return {
    name: product?.name ?? "",
    status: product?.status ?? "ACTIVE",
    principalMin: product?.principalMin ?? 0,
    principalMax: product?.principalMax ?? 0,
    interestRatePct: product?.interestRatePct ?? 0,
    processingFeePct: product?.processingFeePct ?? 0,
    tenureMinMonths: product?.tenureMinMonths ?? 1,
    tenureMaxMonths: product?.tenureMaxMonths ?? 12,
  };
}

export function ProductEditDialog({
  open,
  onOpenChange,
  product,
  onConfirm,
  loading = false,
  errorMessage = null,
}: ProductEditDialogProps) {
  const form = useForm<EditProductFormValues>({
    resolver: zodResolver(EditProductFormSchema),
    defaultValues: defaultsFor(product),
    mode: "onSubmit",
  });

  const nameRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) return;
    form.reset(defaultsFor(product));
    const id = window.setTimeout(() => nameRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- defaults rebuilt from product on open
  }, [open, product?.id]);

  const handleSubmit = async (values: EditProductFormValues) => {
    await onConfirm({
      input: {
        name: values.name.trim(),
        status: values.status,
        principalMin: values.principalMin,
        principalMax: values.principalMax,
        interestRatePct: values.interestRatePct,
        processingFeePct: values.processingFeePct,
        tenureMinMonths: values.tenureMinMonths,
        tenureMaxMonths: values.tenureMaxMonths,
        idempotencyKey: newIdempotencyKey(),
      },
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Edit2 className="text-primary h-5 w-5" aria-hidden="true" />
            <DialogTitle>
              Edit product
              {product ? (
                <>
                  {" "}
                  <span className="text-foreground-muted font-mono text-sm">({product.code})</span>
                </>
              ) : null}
            </DialogTitle>
          </div>
          <DialogDescription>
            Status and pricing changes are versioned in the product audit stream.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
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
                    <Select value={field.value} onValueChange={(v) => field.onChange(v)}>
                      <SelectTrigger size="sm" data-slot="products-edit-status" aria-label="Status">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="ACTIVE">Active</SelectItem>
                        <SelectItem value="INACTIVE">Inactive</SelectItem>
                      </SelectContent>
                    </Select>
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
                      data-slot="products-edit-principal-max"
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
                      data-slot="products-edit-tenure-max"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

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
              {loading ? "Saving…" : "Save changes"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
