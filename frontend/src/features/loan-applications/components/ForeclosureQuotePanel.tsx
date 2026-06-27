import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { InlineErrorAlert } from "@/components/app/feedback/InlineErrorAlert";
import { StatusBadge, type AnyStatus } from "@/components/app/status/StatusBadge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { formatDate, formatINR } from "@/lib/format";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { LoanStatus } from "@/types";
import {
  executeForeclosureQuote,
  fetchForeclosureQuotes,
  requestForeclosureQuote,
} from "../api-detail";
import { invalidateLoanApplicationDetailCaches } from "../hooks/useLoanApplicationMutations";
import type { LoanApplicationDetail, LoanForeclosureQuote } from "../types";

export interface ForeclosureQuotePanelProps {
  detail: LoanApplicationDetail;
  onExecuted?: () => void;
}

const ELIGIBLE_STATUSES = new Set<LoanStatus>(["DISBURSED", "UNDER_REPAYMENT"]);

function foreclosureQuotesQueryKey(applicationId: string) {
  return ["loan-application", applicationId, "foreclosure-quotes"] as const;
}

function todayDate(): string {
  return new Date().toISOString().slice(0, 10);
}

function latestQuote(
  quotes: readonly LoanForeclosureQuote[] | undefined,
): LoanForeclosureQuote | null {
  if (!quotes || quotes.length === 0) return null;
  return (
    [...quotes].sort((a, b) => {
      if (a.status === "ACTIVE" && b.status !== "ACTIVE") return -1;
      if (b.status === "ACTIVE" && a.status !== "ACTIVE") return 1;
      return b.createdAt.localeCompare(a.createdAt);
    })[0] ?? null
  );
}

function QuoteAmountRow({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-foreground-muted">{label}</dt>
      <dd className="tabular-nums">{formatINR(value, { decimals: 2 })}</dd>
    </div>
  );
}

export function ForeclosureQuotePanel({ detail, onExecuted }: ForeclosureQuotePanelProps) {
  const queryClient = useQueryClient();
  const [effectiveDate, setEffectiveDate] = useState(todayDate);
  const [reference, setReference] = useState("");
  const [note, setNote] = useState("");
  const applicationId = detail.application.id;
  const isEligible = detail.account !== null && ELIGIBLE_STATUSES.has(detail.application.status);

  const quotesQuery = useQuery({
    queryKey: foreclosureQuotesQueryKey(applicationId),
    queryFn: () => fetchForeclosureQuotes(applicationId),
    enabled: isEligible,
    staleTime: 15_000,
  });

  const quote = useMemo(() => latestQuote(quotesQuery.data), [quotesQuery.data]);
  const canExecute = quote?.status === "ACTIVE" && reference.trim().length > 0;

  const requestMutation = useMutation({
    mutationFn: () => requestForeclosureQuote(applicationId, { effectiveDate }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: foreclosureQuotesQueryKey(applicationId) });
      toast.success("Foreclosure quote generated.");
    },
  });

  const executeMutation = useMutation({
    mutationFn: () => {
      if (!quote) throw new Error("Generate a quote before executing foreclosure.");
      return executeForeclosureQuote(applicationId, {
        quoteId: quote.id,
        settlementDate: quote.effectiveDate,
        reference: reference.trim(),
        note: note.trim() || null,
        idempotencyKey: newIdempotencyKey(),
      });
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: foreclosureQuotesQueryKey(applicationId) });
      invalidateLoanApplicationDetailCaches(queryClient, applicationId);
      toast.success("Foreclosure executed.");
      onExecuted?.();
    },
  });

  if (!isEligible) return null;

  const errorMessage =
    mapApiErrorMessage(requestMutation.error, "") ||
    mapApiErrorMessage(executeMutation.error, "") ||
    mapApiErrorMessage(quotesQuery.error, "");

  return (
    <Card size="sm" data-slot="foreclosure-quote-panel">
      <CardHeader>
        <CardTitle>Foreclosure</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <InlineErrorAlert message={errorMessage || null} />

        <div className="grid gap-3 md:grid-cols-[minmax(12rem,16rem)_auto] md:items-end">
          <div className="flex flex-col gap-1">
            <Label htmlFor="foreclosure-effective-date">Quote effective date</Label>
            <Input
              id="foreclosure-effective-date"
              type="date"
              value={effectiveDate}
              onChange={(event) => setEffectiveDate(event.target.value)}
              disabled={requestMutation.isPending || executeMutation.isPending}
            />
          </div>
          <Button
            type="button"
            variant="outline"
            onClick={() => requestMutation.mutate()}
            disabled={!effectiveDate || requestMutation.isPending || executeMutation.isPending}
          >
            {requestMutation.isPending ? "Generating..." : "Request quote"}
          </Button>
        </div>

        {quotesQuery.isPending ? (
          <p className="text-foreground-muted text-sm">Loading foreclosure quotes...</p>
        ) : quote ? (
          <div className="border-border rounded-md border p-3" data-slot="foreclosure-quote">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <p className="text-sm font-medium">Quote version {quote.version}</p>
                <p className="text-foreground-muted text-xs">
                  Effective {formatDate(quote.effectiveDate)}
                </p>
              </div>
              <StatusBadge status={quote.status as AnyStatus} />
            </div>
            <dl className="mt-3 grid gap-3 text-sm md:grid-cols-3">
              <QuoteAmountRow label="Outstanding principal" value={quote.outstandingPrincipal} />
              <QuoteAmountRow label="Outstanding interest" value={quote.outstandingInterest} />
              <QuoteAmountRow label="Settlement amount" value={quote.settlementAmount} />
            </dl>
          </div>
        ) : (
          <p className="text-foreground-muted text-sm">
            No foreclosure quote has been generated for this loan yet.
          </p>
        )}

        <div className="grid gap-3 md:grid-cols-2">
          <div className="flex flex-col gap-1">
            <Label htmlFor="foreclosure-reference">Settlement reference</Label>
            <Input
              id="foreclosure-reference"
              value={reference}
              onChange={(event) => setReference(event.target.value)}
              placeholder="Bank UTR or settlement reference"
              disabled={!quote || executeMutation.isPending}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="foreclosure-note">Note</Label>
            <Textarea
              id="foreclosure-note"
              value={note}
              onChange={(event) => setNote(event.target.value)}
              placeholder="Optional context for the audit log"
              disabled={!quote || executeMutation.isPending}
            />
          </div>
        </div>

        <div className="flex justify-end">
          <Button
            type="button"
            onClick={() => executeMutation.mutate()}
            disabled={!canExecute || executeMutation.isPending || requestMutation.isPending}
          >
            {executeMutation.isPending ? "Executing..." : "Execute quote"}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
