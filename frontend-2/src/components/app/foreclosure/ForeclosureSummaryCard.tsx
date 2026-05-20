import { useState } from "react";
import { format, parseISO } from "date-fns";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";

export interface ForeclosureSummaryCardProps {
  /** Outstanding principal as on `quotedAt`. */
  principalOutstanding: number;
  /** Interest accrued on the outstanding principal up to `quotedAt`. */
  accruedInterest: number;
  /** Foreclosure / pre-closure charges per the loan product. */
  foreclosureCharges: number;
  /** principal + accrued interest + charges = total payoff at `quotedAt`. */
  totalPayoff: number;
  /** ISO timestamp at which the quote was generated. */
  quotedAt: string;
  /** ISO timestamp after which the quote is no longer valid (BR-9). */
  quoteValidUntil: string;
}

/**
 * Read-only summary of a foreclosure quote.
 *
 * Surfaces the four amounts (principal outstanding, accrued interest,
 * foreclosure charges, total payoff) plus a freshness badge — BR-9 requires
 * approving foreclosure to use a non-expired quote, so a stale quote must
 * be visually obvious before any approval action is taken.
 *
 * Numbers render with `tabular-nums` and a `<dl>` is used so AT consumers
 * receive proper key/value pairing.
 */
export function ForeclosureSummaryCard({
  principalOutstanding,
  accruedInterest,
  foreclosureCharges,
  totalPayoff,
  quotedAt,
  quoteValidUntil,
}: ForeclosureSummaryCardProps) {
  const validUntilDate = parseISO(quoteValidUntil);
  // Lazy `useState` initializer keeps the read pure for react-hooks/purity.
  // The component re-mounts/re-renders whenever `quoteValidUntil` changes,
  // and the parent owns refresh cadence.
  const [now] = useState<number>(() => Date.now());
  const isStale = now > validUntilDate.valueOf();
  const formattedValidUntil = format(validUntilDate, "dd MMM yyyy HH:mm");
  const formattedQuotedAt = format(parseISO(quotedAt), "dd MMM yyyy HH:mm");

  const rows: Array<{ label: string; value: number; emphasis?: boolean }> = [
    { label: "Principal outstanding", value: principalOutstanding },
    { label: "Accrued interest", value: accruedInterest },
    { label: "Foreclosure charges", value: foreclosureCharges },
    { label: "Total payoff", value: totalPayoff, emphasis: true },
  ];

  return (
    <Card data-slot="foreclosure-summary-card">
      <CardHeader className="border-b">
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <CardTitle>Foreclosure quote</CardTitle>
            <p className="text-foreground-muted text-xs">
              Quoted{" "}
              <time dateTime={quotedAt} className="tabular-nums">
                {formattedQuotedAt}
              </time>
            </p>
          </div>
          {isStale ? (
            <Badge variant="destructive" data-slot="quote-stale-badge">
              Quote stale
            </Badge>
          ) : (
            <span
              data-slot="quote-fresh-indicator"
              className="text-foreground-muted text-xs"
            >
              Quote fresh — expires{" "}
              <time dateTime={quoteValidUntil} className="tabular-nums">
                {formattedValidUntil}
              </time>
            </span>
          )}
        </div>
      </CardHeader>
      <CardContent>
        <dl className="divide-border grid grid-cols-1 divide-y">
          {rows.map((row) => (
            <div
              key={row.label}
              className={cn(
                "flex items-center justify-between gap-4 py-2 text-sm",
                row.emphasis && "font-semibold",
              )}
              data-slot="foreclosure-summary-row"
            >
              <dt className="text-foreground-muted">{row.label}</dt>
              <dd className="tabular-nums">{formatINR(row.value)}</dd>
            </div>
          ))}
        </dl>
      </CardContent>
    </Card>
  );
}
