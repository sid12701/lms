/**
 * ProductEditDialog â€” RHF + Zod update form, sibling schema in `./schema.ts`.
 *
 * Re-uses the create dialog's numeric/RHF wiring but excludes `code`
 * (immutable) and adds a `status` select. The backend maps a status-only
 * delta to a STATUS_CHANGED audit event; all other deltas map to UPDATED.
 */
import { useRef } from "react";
import { useSyncOnOpen } from "@/lib/hooks/use-sync-on-open";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Edit2 } from "lucide-react";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
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
import { FormDialogHeader } from "@/components/app/forms/FormDialogHeader";
import { ProductPricingFields } from "./ProductPricingFields";
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

  useSyncOnOpen(open, () => {
    if (product) form.reset(defaultsFor(product));
  });
  useFocusOnOpen(open, nameRef);

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
        <FormDialogHeader
          icon={Edit2}
          iconClassName="text-primary"
          title={
            <>
              Edit product
              {product ? (
                <>
                  {" "}
                  <span className="text-foreground-muted font-mono text-sm">({product.code})</span>
                </>
              ) : null}
            </>
          }
          description="Status and pricing changes are versioned in the product audit stream."
        />

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

          <ProductPricingFields
            control={form.control}
            principalMaxSlot="products-edit-principal-max"
            tenureMaxSlot="products-edit-tenure-max"
          />

          <FormDialogErrorAlert message={errorMessage} />

          <ConfirmDialogFooter
            loading={loading}
            onCancel={() => onOpenChange(false)}
            submitLabel="Save changes"
            loadingLabel="Savingâ€¦"
          />
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
