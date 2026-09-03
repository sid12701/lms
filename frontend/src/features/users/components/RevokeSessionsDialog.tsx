import { useRef, useState } from "react";
import { useFocusOnOpen } from "@/lib/hooks/use-focus-on-open";
import { LogOut } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { newIdempotencyKey } from "@/lib/idempotency";

export interface RevokeSessionsConfirmArgs {
  reason?: string;
  idempotencyKey: string;
}

export interface RevokeSessionsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  username: string;
  onConfirm: (args: RevokeSessionsConfirmArgs) => Promise<void> | void;
  loading?: boolean;
  errorMessage?: string | null;
}

export function RevokeSessionsDialog({
  open,
  onOpenChange,
  username,
  onConfirm,
  loading = false,
  errorMessage = null,
}: RevokeSessionsDialogProps) {
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  const [reason, setReason] = useState("");

  useFocusOnOpen(open, confirmRef);

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setReason("");
    }
    onOpenChange(nextOpen);
  };

  const handleConfirm = async () => {
    await onConfirm({
      reason: reason.trim() || undefined,
      idempotencyKey: newIdempotencyKey(),
    });
    setReason("");
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <LogOut className="text-warning h-5 w-5" aria-hidden="true" />
            <DialogTitle>Revoke sessions</DialogTitle>
          </div>
          <DialogDescription>
            This will sign <strong>{username}</strong> out of every device. They will need to log in
            again. Continue?
          </DialogDescription>
        </DialogHeader>

        {errorMessage ? (
          <div
            role="alert"
            className="border-danger/30 bg-danger/5 text-danger rounded-container border p-3 text-sm"
          >
            {errorMessage}
          </div>
        ) : null}

        <div className="flex flex-col gap-2">
          <Label htmlFor="revoke-sessions-reason">Reason (optional)</Label>
          <Textarea
            id="revoke-sessions-reason"
            data-slot="revoke-sessions-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="e.g. Suspected compromise"
            rows={3}
            disabled={loading}
          />
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => handleOpenChange(false)}
            disabled={loading}
          >
            Cancel
          </Button>
          <Button
            type="button"
            variant="destructive"
            onClick={handleConfirm}
            disabled={loading}
            ref={confirmRef}
            data-slot="revoke-sessions-confirm"
          >
            {loading ? "Revoking…" : "Revoke sessions"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
