/**
 * ProductMappingDialog — pick the LSPs a product is offered to.
 *
 * RHF + Zod simply for symmetry with the other dialogs; the form payload is
 * a single `lspIds` array. The backend emits a MAPPING_CHANGED audit row.
 */
import { useRef, useState } from "react";
import { useSyncOnOpen } from "@/lib/hooks/use-sync-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Network } from "lucide-react";
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
import { ConfirmDialogFooter } from "@/components/app/forms/ConfirmDialogFooter";
import { FormDialogErrorAlert } from "@/components/app/forms/FormDialogErrorAlert";
import { FormShell } from "@/components/app/forms/FormShell";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { LoanProduct, UpdateProductMappingInput } from "../types";
import { LspMultiSelect } from "./LspMultiSelect";
import type { LspChoice } from "../hooks/useLspChoices";
import { ProductMappingFormSchema, type ProductMappingFormValues } from "./schema";

export interface ProductMappingConfirmArgs {
  input: UpdateProductMappingInput;
}

export interface ProductMappingDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  product: LoanProduct | null;
  initialLspIds: readonly string[];
  lspChoices: readonly LspChoice[];
  onConfirm: (args: ProductMappingConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

export function ProductMappingDialog({
  open,
  onOpenChange,
  product,
  initialLspIds,
  lspChoices,
  onConfirm,
  loading = false,
  errorMessage = null,
}: ProductMappingDialogProps) {
  const form = useForm<ProductMappingFormValues>({
    resolver: zodResolver(ProductMappingFormSchema),
    defaultValues: { lspIds: [...initialLspIds] },
    mode: "onSubmit",
  });

  const containerRef = useRef<HTMLDivElement | null>(null);

  const focusMappingTrigger = () => {
    window.setTimeout(() => {
      const trigger = containerRef.current?.querySelector<HTMLButtonElement>(
        "[data-slot='lsp-multi-select-trigger']",
      );
      trigger?.focus();
    }, 0);
  };

  useSyncOnOpen(open, () => {
    form.reset({ lspIds: [...initialLspIds] });
    focusMappingTrigger();
  });

  const mappingKey = `${product?.id ?? ""}:${initialLspIds.join(",")}`;
  const [prevMappingKey, setPrevMappingKey] = useState(mappingKey);
  if (open && mappingKey !== prevMappingKey) {
    setPrevMappingKey(mappingKey);
    form.reset({ lspIds: [...initialLspIds] });
  }

  const handleSubmit = async (values: ProductMappingFormValues) => {
    await onConfirm({
      input: {
        lspIds: values.lspIds,
        idempotencyKey: newIdempotencyKey(),
      },
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Network className="text-primary-tinted h-5 w-5" aria-hidden="true" />
            <DialogTitle>
              Edit LSP mapping
              {product ? (
                <>
                  {" "}
                  <span className="text-foreground-muted font-mono text-sm">({product.code})</span>
                </>
              ) : null}
            </DialogTitle>
          </div>
          <DialogDescription>
            Replace the full list of LSPs this product is offered to. Mapping changes append a row
            to the product audit stream.
          </DialogDescription>
        </DialogHeader>

        <div ref={containerRef}>
          <FormShell form={form} onSubmit={handleSubmit}>
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
                    Tick LSPs from the dropdown. Leaving the list empty unmaps the product from
                    every LSP.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormDialogErrorAlert message={errorMessage} />

            <ConfirmDialogFooter
              loading={loading}
              onCancel={() => onOpenChange(false)}
              submitLabel="Save mapping"
              loadingLabel="Saving…"
            />
          </FormShell>
        </div>
      </DialogContent>
    </Dialog>
  );
}
