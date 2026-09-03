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
  /**
   * Marks a submit that cannot be undone — currently only the disbursement
   * debit leg.
   *
   * The audit's finding was that the irreversible action was styled like every
   * other action: the confirm button was the same blue, height and radius as
   * "Move to closed". Weight is the signal here rather than colour, because
   * the Reserved Red Rule keeps `destructive` for money that failed and a
   * record that will be lost — and a disbursement is neither. It is the
   * *intended* outcome; it simply cannot be taken back.
   */
  submitIrreversible?: boolean;
  /** Stable selector for an integration-critical submit action. */
  submitDataSlot?: string;
}

export function ConfirmDialogFooter({
  loading = false,
  submitDisabled = false,
  onCancel,
  submitLabel,
  loadingLabel,
  submitVariant = "default",
  submitIrreversible = false,
  submitDataSlot,
}: ConfirmDialogFooterProps) {
  return (
    <DialogFooter>
      <Button type="button" variant="outline" onClick={onCancel} disabled={loading}>
        Cancel
      </Button>
      <Button
        type="submit"
        variant={submitVariant}
        size={submitIrreversible ? "lg" : undefined}
        disabled={loading || submitDisabled}
        data-slot={submitDataSlot}
        data-irreversible={submitIrreversible ? "true" : undefined}
        className={submitIrreversible ? "font-semibold" : undefined}
      >
        {loading ? (loadingLabel ?? "Working…") : submitLabel}
      </Button>
    </DialogFooter>
  );
}
