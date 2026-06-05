import { useState } from "react";
import { AlertTriangle } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

export interface ConfirmDestructiveDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  /** Label for the destructive confirmation button (e.g. "Invalidate loan"). */
  confirmLabel: string;
  /** Optional cancel button label; defaults to "Cancel". */
  cancelLabel?: string;
  /** If provided, the user must type this exact string before confirm enables. */
  typedConfirmation?: string;
  onConfirm: () => void | Promise<void>;
  /** Disables inputs and shows a working state on the confirm button. */
  loading?: boolean;
}

/**
 * Generic destructive-action dialog. Wraps shadcn `Dialog` with a danger
 * heading row, a description, and a danger-styled confirm button. When
 * `typedConfirmation` is provided the user is required to type the exact
 * string to enable the confirm button — used for high-impact actions like
 * loan invalidation.
 */
export function ConfirmDestructiveDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel = "Cancel",
  typedConfirmation,
  onConfirm,
  loading = false,
}: ConfirmDestructiveDialogProps) {
  const [typed, setTyped] = useState("");

  const requiresType = Boolean(typedConfirmation);
  const matches = !requiresType || typed === typedConfirmation;
  const disabled = loading || !matches;

  const handleConfirm = async () => {
    await onConfirm();
  };

  // Reset the typed value whenever the dialog closes (the parent flips
  // `open` to false). This avoids a `useEffect` that would synchronously
  // call setState, while still guaranteeing a fresh prompt every open.
  const handleOpenChange = (next: boolean) => {
    if (!next) setTyped("");
    onOpenChange(next);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <AlertTriangle className="text-danger h-5 w-5" aria-hidden="true" />
            <DialogTitle>{title}</DialogTitle>
          </div>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        {requiresType ? (
          <div className="flex flex-col gap-2">
            <Label htmlFor="confirm-typed-input" className="text-sm">
              Type{" "}
              <code
                data-slot="typed-token"
                className="bg-surface-muted rounded px-1 py-0.5 font-mono text-xs"
              >
                {typedConfirmation}
              </code>{" "}
              to confirm
            </Label>
            <Input
              id="confirm-typed-input"
              autoComplete="off"
              autoCapitalize="off"
              spellCheck={false}
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              disabled={loading}
              aria-invalid={typed.length > 0 && !matches}
              data-state={matches ? "valid" : "invalid"}
            />
          </div>
        ) : null}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => handleOpenChange(false)}
            disabled={loading}
          >
            {cancelLabel}
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={disabled}
            onClick={handleConfirm}
            className={cn(loading && "opacity-80")}
          >
            {loading ? "Working…" : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
