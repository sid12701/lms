/**
 * Create-API-client dialog.
 *
 * Two-phase flow:
 *   1. Operator fills name + LSP + (optional) IP allow-list. Submit fires
 *      `useCreateApiClient` and returns the cleartext secret.
 *   2. The dialog body swaps to the `ApiSecretReveal` banner + the
 *      `ApiClientSecretMeta` card. Clicking "I've saved it" closes the
 *      dialog.
 *
 * The cleartext secret is held in component-local state only — once the
 * dialog unmounts (acknowledge or cancel), the cleartext is gone forever.
 * No persistence; no debug logging.
 */
import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { KeyRound } from "lucide-react";
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { FormShell } from "@/components/app/forms/FormShell";
import {
  ApiClientSecretMeta,
  ApiSecretReveal,
} from "@/components/app/secrets";
import { newIdempotencyKey } from "@/lib/idempotency";
import { IpAllowListEditor } from "./IpAllowListEditor";
import {
  CreateApiClientSchema,
  type CreateApiClientValues,
} from "./schema";
import type { ApiClientRow, CreateApiClientResponse } from "../types";

export interface LspChoice {
  id: string;
  name: string;
}

export interface ApiClientCreateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lspOptions: readonly LspChoice[];
  /**
   * Issue the create mutation. The dialog mints the BR-5 idempotency key
   * internally so callers don't have to.
   */
  onCreate: (input: {
    name: string;
    lspId: string;
    ipAllowList: string[];
    idempotencyKey: string;
  }) => Promise<CreateApiClientResponse>;
  loading?: boolean;
  errorMessage?: string | null;
}

export function ApiClientCreateDialog({
  open,
  onOpenChange,
  lspOptions,
  onCreate,
  loading = false,
  errorMessage = null,
}: ApiClientCreateDialogProps) {
  const [revealed, setRevealed] = useState<{
    client: ApiClientRow;
    clientSecret: string;
  } | null>(null);

  const form = useForm<CreateApiClientValues>({
    resolver: zodResolver(CreateApiClientSchema),
    defaultValues: { name: "", lspId: "", ipAllowList: [] },
    mode: "onSubmit",
  });

  const nameRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) {
      form.reset({ name: "", lspId: "", ipAllowList: [] });
      setRevealed(null);
      return;
    }
    const t = window.setTimeout(() => nameRef.current?.focus(), 0);
    return () => window.clearTimeout(t);
  }, [open, form]);

  const handleSubmit = async (values: CreateApiClientValues) => {
    try {
      const res = await onCreate({
        name: values.name,
        lspId: values.lspId,
        ipAllowList: values.ipAllowList,
        idempotencyKey: newIdempotencyKey(),
      });
      setRevealed({ client: res.client, clientSecret: res.clientSecret });
    } catch {
      // Surface via the `errorMessage` prop. Keep the form open to retry.
    }
  };

  const handleAcknowledge = () => {
    setRevealed(null);
    onOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <KeyRound aria-hidden="true" className="text-progress h-5 w-5" />
            <DialogTitle>
              {revealed ? "Save the new API client secret" : "New API client"}
            </DialogTitle>
          </div>
          <DialogDescription>
            {revealed
              ? "The secret below is shown exactly once. Copy it to your password manager before continuing."
              : "Create an API credential for an LSP integration. The cleartext secret will be shown once after submit."}
          </DialogDescription>
        </DialogHeader>

        {revealed ? (
          <div data-slot="api-client-create-revealed" className="flex flex-col gap-4">
            <ApiSecretReveal
              secret={revealed.clientSecret}
              clientLabel={revealed.client.name}
              onAcknowledge={handleAcknowledge}
            />
            <ApiClientSecretMeta
              keyIdPrefix={revealed.client.clientId}
              createdAt={revealed.client.createdAt}
              lastRotatedAt={null}
              ipAllowlistCount={revealed.client.ipAllowlistCount}
            />
          </div>
        ) : (
          <FormShell form={form} onSubmit={handleSubmit}>
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
                      placeholder="e.g. Acme NBFC — Production"
                      maxLength={120}
                    />
                  </FormControl>
                  <FormDescription>
                    Human-readable label shown in the admin list.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="lspId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>LSP</FormLabel>
                  <FormControl>
                    <Select
                      value={field.value || undefined}
                      onValueChange={field.onChange}
                    >
                      <SelectTrigger
                        size="sm"
                        aria-label="LSP"
                        data-slot="api-client-create-lsp"
                      >
                        <SelectValue placeholder="Select an LSP" />
                      </SelectTrigger>
                      <SelectContent>
                        {lspOptions.map((lsp) => (
                          <SelectItem key={lsp.id} value={lsp.id}>
                            {lsp.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </FormControl>
                  <FormDescription>
                    The LSP this credential authorises against.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="ipAllowList"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>IP allow-list</FormLabel>
                  <FormControl>
                    <IpAllowListEditor
                      value={field.value}
                      onChange={field.onChange}
                    />
                  </FormControl>
                  <FormDescription>
                    Optional. Each entry is an IPv4 address or CIDR (BR-8).
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
                {loading ? "Creating…" : "Create API client"}
              </Button>
            </DialogFooter>
          </FormShell>
        )}
      </DialogContent>
    </Dialog>
  );
}
