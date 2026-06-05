import { Webhook } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { cn } from "@/lib/utils";
import { formatRelative } from "@/lib/format";
import { useLoanApplicationWebhooks } from "../../hooks/useLoanApplicationWebhooks";
import type { LoanApplicationWebhookDelivery } from "../../types";

export interface WebhooksTabProps {
  applicationId: string;
}

const STATUS_CLASSES: Record<LoanApplicationWebhookDelivery["status"], string> = {
  DELIVERED: "border-success/30 bg-success/10 text-success",
  FAILED: "border-warning/30 bg-warning/10 text-warning",
  DEAD_LETTERED: "border-danger/30 bg-danger/10 text-danger",
  PENDING: "border-border bg-surface-muted text-foreground-muted",
};

function StatusPill({ status }: { status: LoanApplicationWebhookDelivery["status"] }) {
  return (
    <Badge
      data-slot="webhook-status"
      data-status={status}
      className={cn("border", STATUS_CLASSES[status])}
    >
      {status.replace(/_/g, " ").toLowerCase()}
    </Badge>
  );
}

/**
 * Webhooks tab — surfaces every outbox delivery attempt the BR-15 webhook
 * dispatcher has produced for this application. Sorted newest-first by
 * `createdAt`; the relative-time label uses `formatRelative` so screen
 * readers still get a human anchor.
 */
export function WebhooksTab({ applicationId }: WebhooksTabProps) {
  const query = useLoanApplicationWebhooks(applicationId);

  if (query.isPending) {
    return (
      <div data-slot="webhooks-tab-loading" className="flex flex-col gap-3">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }

  if (query.isError) {
    return (
      <ErrorState
        title="Couldn't load webhook deliveries"
        description="The outbox feed failed to load. Try again, and contact support if the problem persists."
        retry={{ label: "Retry", onClick: () => void query.refetch() }}
      />
    );
  }

  const deliveries = [...(query.data?.deliveries ?? [])].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  );

  if (deliveries.length === 0) {
    return (
      <EmptyState
        icon={Webhook}
        title="No webhook deliveries yet"
        description="Outbound events for this application will appear here as they're dispatched."
      />
    );
  }

  return (
    <div data-slot="webhooks-tab" className="border-border overflow-hidden rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead scope="col">Event type</TableHead>
            <TableHead scope="col">Endpoint</TableHead>
            <TableHead scope="col">Status</TableHead>
            <TableHead scope="col" className="text-right">
              Attempts
            </TableHead>
            <TableHead scope="col">Last attempt</TableHead>
            <TableHead scope="col">Last error</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {deliveries.map((delivery) => (
            <TableRow key={delivery.id} data-testid={`webhook-row-${delivery.id}`}>
              <TableCell className="font-mono text-xs">{delivery.eventType}</TableCell>
              <TableCell className="text-foreground-muted max-w-[20rem] truncate font-mono text-xs">
                {delivery.endpoint}
              </TableCell>
              <TableCell>
                <StatusPill status={delivery.status} />
              </TableCell>
              <TableCell className="text-right tabular-nums">{delivery.attemptCount}</TableCell>
              <TableCell className="text-foreground-muted text-xs">
                {delivery.lastAttemptAt ? formatRelative(delivery.lastAttemptAt) : "—"}
              </TableCell>
              <TableCell className="text-danger max-w-[24rem] truncate text-xs">
                {delivery.lastError ?? "—"}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

export default WebhooksTab;
