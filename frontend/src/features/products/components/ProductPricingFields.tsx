import type { Control, FieldPath, FieldValues } from "react-hook-form";
import { FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";

type PricingShape = {
  principalMin: number;
  principalMax: number;
  interestRatePct: number;
  processingFeePct: number;
  tenureMinMonths: number;
  tenureMaxMonths: number;
};

export interface ProductPricingFieldsProps<T extends FieldValues & PricingShape> {
  control: Control<T>;
  principalMinSlot?: string;
  principalMaxSlot?: string;
  tenureMaxSlot?: string;
}

export function ProductPricingFields<T extends FieldValues & PricingShape>({
  control,
  principalMinSlot,
  principalMaxSlot,
  tenureMaxSlot,
}: ProductPricingFieldsProps<T>) {
  return (
    <>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField
          control={control}
          name={"principalMin" as FieldPath<T>}
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
                  data-slot={principalMinSlot}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name={"principalMax" as FieldPath<T>}
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
                  data-slot={principalMaxSlot}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField
          control={control}
          name={"interestRatePct" as FieldPath<T>}
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
          control={control}
          name={"processingFeePct" as FieldPath<T>}
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
          control={control}
          name={"tenureMinMonths" as FieldPath<T>}
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
          control={control}
          name={"tenureMaxMonths" as FieldPath<T>}
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
                  data-slot={tenureMaxSlot}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </div>
    </>
  );
}
