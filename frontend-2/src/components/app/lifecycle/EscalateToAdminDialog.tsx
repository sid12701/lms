import { useEffect, useRef, useState } from "react";
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
import { Textarea } from "@/components/ui/textarea";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { AlertSubjectType } from "@/schemas/alert";

export interface EscalateToAdminDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  subjectType: AlertSubjectType;
  subjectId: string | null;
  /** Optional headline override; defaults to "Escalate to admin". */
  title?: string;
  /** Optional context line above the form. */
  description?: string;
  onConfirm: (args: {
    title: string;
    message: string;
    idempotencyKey: string;
  }) => Promise<void>;
  /** Disables submit while the network call is in-flight. */
  loading?: boolean;
}

const TITLE_MAX = 255;
const MESSAGE_MAX = 1000;

/**
 * OPS_USER escalation surface (Gap #16). Ops surfaces a stuck loan to
 * SYSTEM_ADMIN by emitting an `OPS_USER_ESCALATION` alert. Title + message
 * are required; both are recorded on the alert audit row and visible in
 * the admin alerts inbox.
 */
export function EscalateToAdminDialog({
  open,
  onOpenChange,
  subjectType: _subjectType,
  subjectId: _subjectId,
  title,
  description,
  onConfirm,
  loading = false,
}: EscalateToAdminDialogProps) {
  const [escalationTitle, setEscalationTitle] = useState("");
  const [message, setMessage] = useState("");
  const [titleError, setTitleError] = useState<string | null>(null);
  const [messageError, setMessageError] = useState<string | null>(null);
  const titleRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) {
      setEscalationTitle("");
      setMessage("");
      setTitleError(null);
      setMessageError(null);
      return;
    }
    const id = window.setTimeout(() => titleRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [open]);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    const trimmedTitle = escalationTitle.trim();
    const trimmedMessage = message.trim();
    let hasError = false;
    if (trimmedTitle.length === 0) {
      setTitleError("Title is required.");
      hasError = true;
    } else {
      setTitleError(null);
    }
    if (trimmedMessage.length === 0) {
      setMessageError("Message is required.");
      hasError = true;
    } else {
      setMessageError(null);
    }
    if (hasError) return;

    await onConfirm({
      title: trimmedTitle,
      message: trimmedMessage,
      idempotencyKey: newIdempotencyKey(),
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <div className="flex items-center gap-2">
            <AlertTriangle className="h-5 w-5 text-warning" aria-hidden="true" />
            <DialogTitle>{title ?? "Escalate to admin"}</DialogTitle>
          </div>
          <DialogDescription>
            {description ??
              "Create a high-severity alert for SYSTEM_ADMIN. Use this when an automated flow is stuck and needs human intervention."}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="escalate-title">Title</Label>
            <Input
              id="escalate-title"
              name="title"
              value={escalationTitle}
              onChange={(e) => setEscalationTitle(e.target.value)}
              maxLength={TITLE_MAX}
              placeholder="e.g. Disbursement adapter has failed 6 times"
              ref={titleRef}
              aria-invalid={titleError ? true : undefined}
              aria-describedby={titleError ? "escalate-title-error" : undefined}
            />
            {titleError ? (
              <p id="escalate-title-error" className="text-danger text-sm">
                {titleError}
              </p>
            ) : null}
          </div>

          <div className="space-y-2">
            <Label htmlFor="escalate-message">Message</Label>
            <Textarea
              id="escalate-message"
              name="message"
              rows={4}
              maxLength={MESSAGE_MAX}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder="Describe what's stuck and what you've tried."
              aria-invalid={messageError ? true : undefined}
              aria-describedby={messageError ? "escalate-message-error" : undefined}
            />
            {messageError ? (
              <p id="escalate-message-error" className="text-danger text-sm">
                {messageError}
              </p>
            ) : (
              <p className="text-foreground-muted text-xs">
                Up to {MESSAGE_MAX} characters. Visible to admins in the alerts inbox.
              </p>
            )}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={loading}
            >
              Cancel
            </Button>
            <Button type="submit" variant="default" disabled={loading}>
              {loading ? "Sending…" : "Send escalation"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export default EscalateToAdminDialog;
