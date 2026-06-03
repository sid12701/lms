/**
 * Audit-event detail "sheet".
 *
 * No `Sheet` primitive exists in this codebase yet, and adding one would
 * pull a new Radix sub-package — forbidden without orchestrator approval
 * (CLAUDE.md hard-don't #5). Instead, we re-use the project's `Dialog`
 * primitive (which already wraps Radix's portaled overlay) and style the
 * content as a right-anchored sheet. The semantics are identical from an
 * a11y standpoint: focus trap, `role=dialog`, ESC to close.
 *
 * The close button is focused via `useRef + setTimeout(focus, 0)` per
 * the binding test-discipline rule #3 (`jsx-a11y/no-autofocus` is
 * enforced project-wide).
 *
 * "View subject" deep-links into:
 *   LOAN_APPLICATION → `/loan-applications/:id`
 *   BORROWER         → `/borrowers/:id`
 *   LOAN_PRODUCT     → `/admin/products/:id`
 *   LOAN_DOCUMENT    → null (no standalone document route yet)
 */
import { useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { Copy, ExternalLink, X } from "lucide-react";
import { Dialog as DialogPrimitive } from "radix-ui";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { AUDIT_STREAM_LABEL, type AuditRow } from "../types";

function subjectHref(row: AuditRow): string | null {
  if (!row.subjectId) return null;
  switch (row.subjectType) {
    case "LOAN_APPLICATION":
      return `/loan-applications/${row.subjectId}`;
    case "BORROWER":
      return `/borrowers/${row.subjectId}`;
    case "LOAN_PRODUCT":
      return `/admin/products/${row.subjectId}`;
    case "APP_USER":
      return `/users`;
    case "API_CLIENT":
      return `/api-clients`;
    case "LOAN_DOCUMENT":
    default:
      return null;
  }
}

function subjectLabel(row: AuditRow): string | null {
  if (!row.subjectId || !row.subjectType) return null;
  switch (row.subjectType) {
    case "LOAN_APPLICATION":
      return "View application";
    case "BORROWER":
      return "View borrower";
    case "LOAN_PRODUCT":
      return "View product";
    case "APP_USER":
      return "View users";
    case "API_CLIENT":
      return "View API clients";
    case "LOAN_DOCUMENT":
      return "Document (no standalone view)";
    default:
      return null;
  }
}

function formatTimestamp(iso: string): string {
  try {
    return new Date(iso).toISOString();
  } catch {
    return iso;
  }
}

async function copyToClipboard(value: string): Promise<void> {
  try {
    if (typeof navigator !== "undefined" && navigator.clipboard) {
      await navigator.clipboard.writeText(value);
    }
  } catch {
    // Best-effort; the value remains visible in the sheet either way.
  }
}

export interface AuditEventDetailSheetProps {
  event: AuditRow | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function AuditEventDetailSheet({ event, open, onOpenChange }: AuditEventDetailSheetProps) {
  const closeRef = useRef<HTMLButtonElement | null>(null);
  const navigate = useNavigate();

  // jsx-a11y/no-autofocus is enforced; the close button is focused after
  // the dialog mounts via setTimeout(0). Test-discipline rule #3.
  useEffect(() => {
    if (!open) return;
    const t = window.setTimeout(() => {
      closeRef.current?.focus();
    }, 0);
    return () => window.clearTimeout(t);
  }, [open, event?.id]);

  if (!event) return null;

  const href = subjectHref(event);
  const subjectCta = subjectLabel(event);

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          data-slot="audit-sheet-overlay"
          className={cn(
            "data-[state=closed]:animate-out data-[state=closed]:fade-out-0",
            "data-[state=open]:animate-in data-[state=open]:fade-in-0",
            "fixed inset-0 z-50 bg-black/50",
          )}
        />
        <DialogPrimitive.Content
          data-slot="audit-sheet-content"
          aria-describedby={undefined}
          className={cn(
            "bg-background border-border",
            "fixed top-0 right-0 z-50 flex h-full w-full max-w-md flex-col gap-4 border-l p-6 shadow-lg outline-none",
            "data-[state=closed]:animate-out data-[state=open]:animate-in",
            "data-[state=closed]:slide-out-to-right data-[state=open]:slide-in-from-right",
            "duration-200",
          )}
        >
          <header className="flex items-start justify-between gap-3">
            <div className="flex flex-col gap-1">
              <Badge variant="outline" className="w-fit text-[10px] font-medium uppercase">
                {AUDIT_STREAM_LABEL[event.stream]}
              </Badge>
              <DialogPrimitive.Title className="text-foreground text-base leading-6 font-semibold">
                {event.headline}
              </DialogPrimitive.Title>
              <p className="text-foreground-muted font-mono text-[11px]">
                {formatTimestamp(event.createdAt)}
              </p>
            </div>
            <DialogPrimitive.Close asChild>
              <Button
                ref={closeRef}
                type="button"
                variant="ghost"
                size="icon"
                aria-label="Close audit event details"
                className="size-7"
              >
                <X aria-hidden="true" className="size-4" />
              </Button>
            </DialogPrimitive.Close>
          </header>

          <dl className="grid grid-cols-[120px_1fr] gap-y-2 text-xs">
            <dt className="text-foreground-muted tracking-wide uppercase">Actor</dt>
            <dd className="text-foreground">
              {event.actorName}
              {event.actorRole ? (
                <span className="text-foreground-muted ml-1">· {event.actorRole}</span>
              ) : null}
            </dd>

            <dt className="text-foreground-muted tracking-wide uppercase">Correlation</dt>
            <dd className="flex items-center gap-1">
              <span
                className="text-foreground font-mono text-[11px] break-all"
                data-slot="audit-sheet-correlation"
              >
                {event.correlationId}
              </span>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`Copy correlation id ${event.correlationId}`}
                onClick={() => void copyToClipboard(event.correlationId)}
                className="size-6 p-0"
              >
                <Copy aria-hidden="true" className="size-3" />
              </Button>
            </dd>

            <dt className="text-foreground-muted tracking-wide uppercase">Subject</dt>
            <dd className="text-foreground">
              {event.subjectType ? (
                <span>
                  {event.subjectType}
                  {event.subjectId ? (
                    <span className="text-foreground-muted ml-1 font-mono text-[11px]">
                      · {event.subjectId}
                    </span>
                  ) : null}
                </span>
              ) : (
                <span className="text-foreground-muted">—</span>
              )}
            </dd>
          </dl>

          {href ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              data-slot="audit-view-subject"
              onClick={() => {
                onOpenChange(false);
                navigate(href);
              }}
              className="w-fit gap-1"
            >
              <ExternalLink aria-hidden="true" className="size-3.5" />
              {subjectCta ?? "View subject"}
            </Button>
          ) : null}

          <section className="flex flex-1 flex-col gap-1 overflow-hidden">
            <h3 className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
              Raw event
            </h3>
            <pre
              data-slot="audit-sheet-raw"
              className="border-border bg-surface-muted text-foreground flex-1 overflow-auto rounded-md border p-3 font-mono text-[11px] leading-4"
            >
              {JSON.stringify(event.raw, null, 2)}
            </pre>
          </section>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
