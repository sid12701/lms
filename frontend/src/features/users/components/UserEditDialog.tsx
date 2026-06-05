import { useRef } from "react";
import { useSyncOnOpen } from "@/lib/hooks/use-sync-on-open";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { UserCog } from "lucide-react";
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
import { newIdempotencyKey } from "@/lib/idempotency";
import type { Role } from "@/schemas/role";
import type { UserStatus } from "@/schemas/user";
import {
  EditUserFormSchema,
  isTenantScopedRole,
  LSP_SELECT_NONE,
  type EditUserFormValues,
} from "./schema";
import type { UserRow } from "../types";

const ROLE_OPTIONS: readonly { value: Role; label: string }[] = [
  { value: "SYSTEM_ADMIN", label: "System admin" },
  { value: "OPS_USER", label: "Ops user" },
  { value: "PRODUCT_ADMIN", label: "Product admin" },
  { value: "LSP_UI_READ", label: "LSP UI — read" },
  { value: "LSP_UI_WRITE", label: "LSP UI — write" },
  { value: "LSP_API_CLIENT", label: "LSP API client" },
];

const STATUS_OPTIONS: readonly { value: UserStatus; label: string }[] = [
  { value: "ACTIVE", label: "Active" },
  { value: "DISABLED", label: "Disabled" },
];

export interface UserEditDialogLspOption {
  id: string;
  name: string;
}

export interface UserEditConfirmArgs {
  email: string;
  role: Role;
  lspId: string | null;
  status: UserStatus;
  idempotencyKey: string;
}

export interface UserEditDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  user: UserRow | null;
  lspOptions: readonly UserEditDialogLspOption[];
  onConfirm: (args: UserEditConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

/**
 * Edit dialog — covers email, role, lspId, and status.
 */
export function UserEditDialog({
  open,
  onOpenChange,
  user,
  lspOptions,
  onConfirm,
  loading = false,
  errorMessage = null,
}: UserEditDialogProps) {
  const form = useForm<EditUserFormValues>({
    resolver: zodResolver(EditUserFormSchema),
    defaultValues: {
      email: "",
      role: "OPS_USER",
      lspId: null,
      status: "ACTIVE",
    },
    mode: "onSubmit",
  });

  const emailRef = useRef<HTMLInputElement | null>(null);

  useSyncOnOpen(open, () => {
    if (user) {
      form.reset({
        email: user.email,
        role: user.role,
        lspId: user.lspId,
        status: user.status,
      });
    }
  });
  useFocusOnOpen(open, emailRef);

  const role = form.watch("role");

  const handleSubmit = async (values: EditUserFormValues) => {
    await onConfirm({
      email: values.email.trim(),
      role: values.role,
      lspId: values.lspId,
      status: values.status,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  const showLspField = isTenantScopedRole(role);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <UserCog className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>Edit user</DialogTitle>
          </div>
          <DialogDescription>
            Update <strong>{user?.username ?? "—"}</strong>. Username cannot be changed after
            creation.
          </DialogDescription>
        </DialogHeader>

        <FormShell form={form} onSubmit={handleSubmit}>
          <FormField
            control={form.control}
            name="email"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Email</FormLabel>
                <FormControl>
                  <Input
                    type="email"
                    maxLength={254}
                    {...field}
                    ref={(el) => {
                      field.ref(el);
                      emailRef.current = el;
                    }}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="role"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Role</FormLabel>
                <FormControl>
                  <Select
                    value={field.value}
                    onValueChange={(v) => {
                      const next = v as Role;
                      field.onChange(next);
                      if (!isTenantScopedRole(next)) {
                        form.setValue("lspId", null, { shouldValidate: false });
                      }
                    }}
                  >
                    <SelectTrigger aria-label="Role" data-slot="user-edit-role">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {ROLE_OPTIONS.map((o) => (
                        <SelectItem key={o.value} value={o.value}>
                          {o.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          {showLspField ? (
            <FormField
              control={form.control}
              name="lspId"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>LSP</FormLabel>
                  <FormControl>
                    <Select
                      value={field.value ?? LSP_SELECT_NONE}
                      onValueChange={(v) => field.onChange(v === LSP_SELECT_NONE ? null : v)}
                    >
                      <SelectTrigger aria-label="LSP" data-slot="user-edit-lsp">
                        <SelectValue placeholder="Select an LSP" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={LSP_SELECT_NONE}>Select an LSP</SelectItem>
                        {lspOptions.map((o) => (
                          <SelectItem key={o.id} value={o.id}>
                            {o.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </FormControl>
                  <FormDescription>Required for tenant-scoped roles.</FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />
          ) : null}

          <FormField
            control={form.control}
            name="status"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Status</FormLabel>
                <FormControl>
                  <Select
                    value={field.value}
                    onValueChange={(v) => field.onChange(v as UserStatus)}
                  >
                    <SelectTrigger aria-label="Status" data-slot="user-edit-status">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {STATUS_OPTIONS.map((o) => (
                        <SelectItem key={o.value} value={o.value}>
                          {o.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
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
              {loading ? "Saving…" : "Save changes"}
            </Button>
          </DialogFooter>
        </FormShell>
      </DialogContent>
    </Dialog>
  );
}
