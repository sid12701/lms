import { useMemo, useState } from "react";
import { useFlushOnClose } from "@/lib/hooks/use-flush-on-close";
import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { InlineErrorAlert } from "@/components/app/feedback/InlineErrorAlert";
import { newIdempotencyKey } from "@/lib/idempotency";
import { markLoanInvalid, type InvalidReasonOption, type MyLoanDetail } from "../api";
import { safeApiMessage } from "../utils";

export interface MarkInvalidDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  applicationId: string;
  reasons: readonly InvalidReasonOption[];
  loadingReasons: boolean;
  reasonsLoadError?: string | null;
  onSuccess: (next: MyLoanDetail) => void;
}

export function MarkInvalidDialog({
  open,
  onOpenChange,
  applicationId,
  reasons,
  loadingReasons,
  reasonsLoadError,
  onSuccess,
}: MarkInvalidDialogProps) {
  const [reasonCode, setReasonCode] = useState<string>("");
  const [reasonText, setReasonText] = useState<string>("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useFlushOnClose(open, () => {
    setReasonCode("");
    setReasonText("");
    setError(null);
    setBusy(false);
  });

  const selected = useMemo(
    () => reasons.find((row) => row.code === reasonCode) ?? null,
    [reasons, reasonCode],
  );
  const requiresText = selected?.requiresText ?? false;
  const canSubmit = !!reasonCode && !busy && (!requiresText || reasonText.trim().length > 0);

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const next = await markLoanInvalid({
        applicationId,
        reasonCode,
        reasonText: requiresText ? reasonText.trim() : undefined,
        idempotencyKey: newIdempotencyKey(),
      });
      onSuccess(next);
      onOpenChange(false);
    } catch (err) {
      setError(safeApiMessage(err, "Failed to mark loan as invalid."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => (!busy ? onOpenChange(next) : undefined)}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <AlertTriangle className="text-warning h-5 w-5" aria-hidden="true" />
            <DialogTitle>Mark loan invalid</DialogTitle>
          </div>
          <DialogDescription>
            This stops further processing of the application. The reason is recorded in the audit
            log against your account.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="invalid-reason">Reason</Label>
            <Select
              value={reasonCode}
              onValueChange={(value) => setReasonCode(value)}
              disabled={loadingReasons || busy}
            >
              <SelectTrigger id="invalid-reason" aria-label="Reason">
                <SelectValue
                  placeholder={loadingReasons ? "Loading reasons…" : "Select a reason"}
                />
              </SelectTrigger>
              <SelectContent>
                {reasons.map((row) => (
                  <SelectItem key={row.code} value={row.code}>
                    {row.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {requiresText ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="invalid-reason-text">Reason details</Label>
              <Textarea
                id="invalid-reason-text"
                rows={3}
                maxLength={500}
                placeholder="Describe why this loan is being marked invalid."
                value={reasonText}
                onChange={(event) => setReasonText(event.target.value)}
                disabled={busy}
              />
            </div>
          ) : null}

          <InlineErrorAlert message={reasonsLoadError ?? null} />

          <InlineErrorAlert message={error} />
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={busy}
          >
            Cancel
          </Button>
          <Button type="button" onClick={handleSubmit} disabled={!canSubmit}>
            {busy ? "Submitting…" : "Mark invalid"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
