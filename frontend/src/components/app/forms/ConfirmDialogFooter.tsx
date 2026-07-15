import { Button } from "@/components/ui/button";
import { DialogFooter } from "@/components/ui/dialog";

export interface ConfirmDialogFooterProps {
  loading?: boolean;
  /** When true, submit stays disabled even if not loading (e.g. awaiting preview). */
  submitDisabled?: boolean;
  onCancel: () => void;
  submitLabel: string;
  loadingLabel?: string;
  submitVariant?: "default" | "destructive";
}

export function ConfirmDialogFooter({
  loading = false,
  submitDisabled = false,
  onCancel,
  submitLabel,
  loadingLabel,
  submitVariant = "default",
}: ConfirmDialogFooterProps) {
  return (
    <DialogFooter>
      <Button type="button" variant="outline" onClick={onCancel} disabled={loading}>
        Cancel
      </Button>
      <Button type="submit" variant={submitVariant} disabled={loading || submitDisabled}>
        {loading ? (loadingLabel ?? "Working…") : submitLabel}
      </Button>
    </DialogFooter>
  );
}
