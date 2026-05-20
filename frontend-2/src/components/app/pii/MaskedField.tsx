import { useState } from "react";
import { Eye, EyeOff } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { maskPan, maskAadhaar, maskMobile, maskAccount, maskEmail } from "@/lib/format";
import type { PiiFieldName } from "@/schemas/audit";
import { PiiRevealDialog } from "./PiiRevealDialog";

export interface MaskedFieldProps {
  fieldName: PiiFieldName;
  /** The clear-text value. Display is masked by default; only revealed after audit. */
  value: string;
  /** Optional override of the masked display (defaults to per-field mask helper). */
  masked?: string;
  /** Subject label surfaced in the reveal dialog (e.g. borrower name). */
  subjectLabel?: string;
  /**
   * Called after the user submits a non-empty reason. Consumers wire this to
   * the audit-write mutation; they receive both the reason and a fresh
   * idempotency key (BR-5).
   */
  onReveal: (args: { reason: string; idempotencyKey: string }) => Promise<void> | void;
  className?: string;
}

const FIELD_LABELS: Record<PiiFieldName, string> = {
  PAN: "PAN",
  AADHAAR: "Aadhaar",
  MOBILE: "Mobile",
  ACCOUNT_NUMBER: "Account number",
  EMAIL: "Email",
};

function defaultMask(field: PiiFieldName, value: string): string {
  switch (field) {
    case "PAN":
      return maskPan(value);
    case "AADHAAR":
      return maskAadhaar(value);
    case "MOBILE":
      return maskMobile(value);
    case "ACCOUNT_NUMBER":
      return maskAccount(value);
    case "EMAIL":
      return maskEmail(value);
  }
}

/**
 * Renders a PII value masked by default. The "Reveal" button opens
 * `PiiRevealDialog`; on confirm the consumer-supplied `onReveal` is awaited
 * (so the audit row is written before we flip state) and the cleartext
 * appears with a "Hide" toggle. State changes are announced via an
 * `aria-live="polite"` region for screen-reader users.
 */
export function MaskedField({
  fieldName,
  value,
  masked,
  subjectLabel,
  onReveal,
  className,
}: MaskedFieldProps) {
  const [revealed, setRevealed] = useState(false);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const display = revealed ? value : (masked ?? defaultMask(fieldName, value));
  const fieldLabel = FIELD_LABELS[fieldName];

  const handleConfirm = async ({
    reason,
    idempotencyKey,
  }: {
    reason: string;
    idempotencyKey: string;
  }) => {
    setBusy(true);
    try {
      await onReveal({ reason, idempotencyKey });
      setRevealed(true);
      setOpen(false);
    } finally {
      setBusy(false);
    }
  };

  return (
    <span
      data-slot="masked-field"
      data-revealed={revealed ? "true" : "false"}
      className={cn("inline-flex items-center gap-2", className)}
    >
      <span
        data-slot="masked-field-value"
        className="font-mono tabular-nums"
        aria-label={revealed ? `${fieldLabel} revealed` : `${fieldLabel} masked`}
      >
        {display}
      </span>

      {revealed ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setRevealed(false)}
          aria-label={`Hide ${fieldLabel}`}
        >
          <EyeOff className="h-4 w-4" aria-hidden="true" />
          <span>Hide</span>
        </Button>
      ) : (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setOpen(true)}
          aria-label={`Reveal ${fieldLabel}`}
        >
          <Eye className="h-4 w-4" aria-hidden="true" />
          <span>Reveal</span>
        </Button>
      )}

      <span data-slot="masked-field-status" aria-live="polite" className="sr-only">
        {revealed ? `${fieldLabel} revealed.` : `${fieldLabel} masked.`}
      </span>

      <PiiRevealDialog
        open={open}
        onOpenChange={(next) => {
          if (!busy) setOpen(next);
        }}
        fieldName={fieldName}
        subjectLabel={subjectLabel}
        onConfirm={handleConfirm}
        loading={busy}
      />
    </span>
  );
}
