import { useRef, useState } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { UserPlus } from "lucide-react";
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
import { newIdempotencyKey } from "@/lib/idempotency";
import type { Role } from "@/schemas/role";
import {
  CreateUserFormSchema,
  isTenantScopedRole,
  LSP_SELECT_NONE,
  type CreateUserFormValues,
} from "./schema";
import { TempPasswordRevealCard } from "./TempPasswordRevealCard";

const ROLE_OPTIONS: readonly { value: Role; label: string }[] = [
  { value: "SYSTEM_ADMIN", label: "System admin" },
  { value: "OPS_USER", label: "Ops user" },
  { value: "PRODUCT_ADMIN", label: "Product admin" },
  { value: "LSP_UI_READ", label: "LSP UI — read" },
  { value: "LSP_UI_WRITE", label: "LSP UI — write" },
  { value: "LSP_API_CLIENT", label: "LSP API client" },
];

export interface UserCreateDialogLspOption {
  id: string;
  name: string;
}

export interface UserCreateConfirmArgs {
  username: string;
  email: string;
  role: Role;
  lspId: string | null;
  idempotencyKey: string;
}

export interface UserCreateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lspOptions: readonly UserCreateDialogLspOption[];
  onConfirm: (args: UserCreateConfirmArgs) => Promise<void> | void;
  /** When the parent mutation has resolved with a temp password, surface it. */
  temporaryPassword?: string | null;
  /** Surfaced as the username hint on the reveal card. */
  createdUsername?: string | null;
  /** When the operator acknowledges the reveal card, the dialog closes. */
  onAcknowledgePassword: () => void;
  loading?: boolean;
  errorMessage?: string | null;
}

/**
 * shadcn-Dialog + RHF + Zod create-user form. After submit, the dialog
 * STAYS OPEN and swaps its body for the `TempPasswordRevealCard` — the
 * operator clicks "I've saved it" to dismiss.
 *
 * LSP field is only visible for tenant-scoped roles
 * (LSP_UI_READ / LSP_UI_WRITE / LSP_API_CLIENT).
 */
export function UserCreateDialog({
  open,
  onOpenChange,
  lspOptions,
  onConfirm,
  temporaryPassword = null,
  createdUsername = null,
  onAcknowledgePassword,
  loading = false,
  errorMessage = null,
}: UserCreateDialogProps) {
  const form = useForm<CreateUserFormValues>({
    resolver: zodResolver(CreateUserFormSchema),
    defaultValues: {
      username: "",
      email: "",
      role: "OPS_USER",
      lspId: null,
    },
    mode: "onSubmit",
  });

  const usernameRef = useRef<HTMLInputElement | null>(null);
  const [revealed, setRevealed] = useState<boolean>(false);
  const [prevTemporaryPassword, setPrevTemporaryPassword] = useState(temporaryPassword);
  if (temporaryPassword !== prevTemporaryPassword) {
    setPrevTemporaryPassword(temporaryPassword);
    if (temporaryPassword) setRevealed(true);
  }

  useFlushOnClose(open, () => {
    form.reset({
      username: "",
      email: "",
      role: "OPS_USER",
      lspId: null,
    });
    setRevealed(false);
  });
  useFocusOnOpen(open, usernameRef);

  const role = form.watch("role");

  const handleSubmit = async (values: CreateUserFormValues) => {
    await onConfirm({
      username: values.username.trim(),
      email: values.email.trim(),
      role: values.role,
      lspId: values.lspId,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  const showLspField = isTenantScopedRole(role);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <UserPlus className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>New user</DialogTitle>
          </div>
          <DialogDescription>
            Create a console or LSP user. A temporary password is generated and shown exactly once.
          </DialogDescription>
        </DialogHeader>

        {revealed && temporaryPassword ? (
          <div data-slot="user-create-revealed" className="flex flex-col gap-4">
            <TempPasswordRevealCard
              password={temporaryPassword}
              username={createdUsername ?? undefined}
              onAcknowledge={onAcknowledgePassword}
            />
          </div>
        ) : (
          <FormShell form={form} onSubmit={handleSubmit}>
            <FormField
              control={form.control}
              name="username"
              render={({ field }) => (
                <FormItem required>
                  <FormLabel>Username</FormLabel>
                  <FormControl>
                    <Input
                      placeholder="e.g. ops.new"
                      maxLength={64}
                      {...field}
                      ref={(el) => {
                        field.ref(el);
                        usernameRef.current = el;
                      }}
                    />
                  </FormControl>
                  <FormDescription>
                    Lowercase letters, digits, dot, hyphen, or underscore.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="email"
              render={({ field }) => (
                <FormItem required>
                  <FormLabel>Email</FormLabel>
                  <FormControl>
                    <Input type="email" placeholder="user@example.com" maxLength={254} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="role"
              render={({ field }) => (
                <FormItem required>
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
                      <SelectTrigger aria-label="Role" data-slot="user-create-role">
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
                  <FormItem required>
                    <FormLabel>LSP</FormLabel>
                    <FormControl>
                      <Select
                        value={field.value ?? LSP_SELECT_NONE}
                        onValueChange={(v) => field.onChange(v === LSP_SELECT_NONE ? null : v)}
                      >
                        <SelectTrigger aria-label="LSP" data-slot="user-create-lsp">
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

            <FormDialogErrorAlert message={errorMessage} />
            <ConfirmDialogFooter
              loading={loading}
              onCancel={() => onOpenChange(false)}
              submitLabel="Create user"
              loadingLabel="Creating…"
            />
          </FormShell>
        )}
      </DialogContent>
    </Dialog>
  );
}
