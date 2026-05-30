/**
 * shadcn-Dialog + RHF + Zod prompt to create a new LSP tenant.
 *
 * Submits mint a BR-5 idempotency key. `code` is canonicalised to uppercase
 * on submit so accidental lowercase input passes the regex.
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
import { CreateLspFormSchema, type CreateLspFormValues } from "./createLspSchema";

export interface LspCreateConfirmArgs {
  code: string;
  name: string;
  idempotencyKey: string;
}

export interface LspCreateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (args: LspCreateConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

export function LspCreateDialog({
  open,
  onOpenChange,
  onConfirm,
  loading = false,
  errorMessage = null,
}: LspCreateDialogProps) {
  const form = useForm<CreateLspFormValues>({
    resolver: zodResolver(CreateLspFormSchema),
    defaultValues: { code: "", name: "" },
    mode: "onSubmit",
  });

  const codeRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) {
      form.reset({ code: "", name: "" });
      return;
    }
    const id = window.setTimeout(() => codeRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [open, form]);

  const handleSubmit = async (values: CreateLspFormValues) => {
    await onConfirm({
      code: values.code.toUpperCase(),
      name: values.name.trim(),
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <Building2 className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>New LSP</DialogTitle>
          </div>
          <DialogDescription>
            Register a new Loan Service Provider tenant. The code is immutable once created and is
            the stable tenant identifier across the platform.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="code"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Code</FormLabel>
                <FormControl>
                  <Input
                    placeholder="LSP-EXAMPLE"
                    maxLength={16}
                    autoCapitalize="characters"
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      codeRef.current = el;
                    }}
                  />
                </FormControl>
                <FormDescription>
                  Uppercase letters, digits, hyphen, or underscore (2-16 chars).
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
                <FormLabel>Display name</FormLabel>
                <FormControl>
                  <Input placeholder="Example Originators Pvt Ltd" maxLength={120} {...field} />
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
            <Button type="submit" disabled={loading}>
              {loading ? "Creating…" : "Create LSP"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
