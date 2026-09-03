/**
 * ProductCreateDialog — RHF + Zod create form, sibling schema in `./schema.ts`.
 *
 * The form mints a BR-5 idempotency key at submit time via
 * `newIdempotencyKey()`. RHF mirrors superRefine errors (principalMax <
 * principalMin, tenureMaxMonths < tenureMinMonths) so inline messages appear
 * before the backend ever sees the payload.
 */
import { useRef } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Layers } from "lucide-react";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import {
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { FormShell } from "@/components/app/forms/FormShell";
import { FormDialogErrorAlert } from "@/components/app/forms/FormDialogErrorAlert";
import { ConfirmDialogFooter } from "@/components/app/forms/ConfirmDialogFooter";
import { FormDialogHeader } from "@/components/app/forms/FormDialogHeader";
import { ProductPricingFields } from "./ProductPricingFields";
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

/*
 * Money and rate fields start empty, not at 0. A pre-filled `0` is a real,
 * submittable value — `interestRatePct` is `.nonnegative()`, so a 0% product
 * would have passed validation — and it forces the operator to clear the field
 * before typing. NaN renders as blank and fails the schema until a value is
 * entered, which is the honest default. Tenure keeps 1/12: those are genuine
 * product conventions, not placeholders.
 */
const DEFAULTS: CreateProductFormValues = {
  code: "",
  name: "",
  principalMin: NaN,
  principalMax: NaN,
  interestRatePct: NaN,
  processingFeePct: NaN,
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

  useFlushOnClose(open, () => form.reset(DEFAULTS));
  useFocusOnOpen(open, codeRef);

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
      <DialogContent className="sm:max-w-2xl">
        <FormDialogHeader
          icon={Layers}
          iconClassName="text-primary-tinted"
          title="New loan product"
          description={
            <>
              Configure a new loan product. The code must be unique across the catalog. Mapping to
              LSPs can be edited later.
            </>
          }
        />

        <FormShell form={form} onSubmit={handleSubmit}>
          <div className="grid grid-cols-1 items-start gap-4 sm:grid-cols-2">
            <FormField
              control={form.control}
              name="code"
              render={({ field }) => (
                <FormItem required>
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
                <FormItem required>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input placeholder="Personal Loan — Standard" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

          <ProductPricingFields
            control={form.control}
            principalMinSlot="products-principal-min"
            principalMaxSlot="products-principal-max"
          />

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

          <FormDialogErrorAlert message={errorMessage} />

          <ConfirmDialogFooter
            loading={loading}
            onCancel={() => onOpenChange(false)}
            submitLabel="Create product"
            loadingLabel="Creating…"
          />
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
