import { useRef, useState } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { KeyRound } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { newIdempotencyKey } from "@/lib/idempotency";
import { TempPasswordRevealCard } from "./TempPasswordRevealCard";

export interface ResetPasswordConfirmArgs {
  idempotencyKey: string;
}

export interface ResetPasswordDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  username: string;
  onConfirm: (args: ResetPasswordConfirmArgs) => Promise<void> | void;
  /** When the parent mutation has resolved with a temp password, surface it. */
  temporaryPassword?: string | null;
  /** When the operator acknowledges the reveal card, the dialog closes. */
  onAcknowledgePassword: () => void;
  loading?: boolean;
  errorMessage?: string | null;
}

/**
 * Reset-password dialog — confirmation prompt that, on submit, swaps its body
 * for the `TempPasswordRevealCard`. The reveal-once UX is identical to the
 * create-user dialog.
 */
export function ResetPasswordDialog({
  open,
  onOpenChange,
  username,
  onConfirm,
  temporaryPassword = null,
  onAcknowledgePassword,
  loading = false,
  errorMessage = null,
}: ResetPasswordDialogProps) {
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  const [revealed, setRevealed] = useState<boolean>(false);
  const [prevTemporaryPassword, setPrevTemporaryPassword] = useState(temporaryPassword);
  if (temporaryPassword !== prevTemporaryPassword) {
    setPrevTemporaryPassword(temporaryPassword);
    if (temporaryPassword) setRevealed(true);
  }

  useFlushOnClose(open, () => setRevealed(false));
  useFocusOnOpen(open, confirmRef);

  const handleConfirm = async () => {
    await onConfirm({ idempotencyKey: newIdempotencyKey() });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <KeyRound className="text-progress h-5 w-5" aria-hidden="true" />
            <DialogTitle>Reset password</DialogTitle>
          </div>
          <DialogDescription>
            Generate a new temporary password for <strong>{username}</strong>. They&rsquo;ll be
            required to change it on next sign-in.
          </DialogDescription>
        </DialogHeader>

        {revealed && temporaryPassword ? (
          <div data-slot="reset-password-revealed" className="flex flex-col gap-4">
            <TempPasswordRevealCard
              password={temporaryPassword}
              username={username}
              onAcknowledge={onAcknowledgePassword}
            />
          </div>
        ) : (
          <>
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
              <Button
                type="button"
                onClick={handleConfirm}
                disabled={loading}
                ref={confirmRef}
                data-slot="reset-password-confirm"
              >
                {loading ? "Resetting…" : "Reset password"}
              </Button>
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}
