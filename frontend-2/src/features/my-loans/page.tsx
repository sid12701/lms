import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Folder, Loader2 } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { buildQueryPath, requestJson, ApiError } from "@/lib/api/http-client";
import { formatINR } from "@/lib/format";

interface LspLoanRow {
  id: string;
  externalLoanId: string | null;
  borrowerFullName: string;
  productName: string;
  requestedAmount: number;
  status: string;
  createdAt: string;
}

interface BackendResponse {
  items?: LspLoanRow[];
  totalCount?: number;
}

export function MyLoansPage() {
  const [items, setItems] = useState<LspLoanRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(true);

  useEffect(() => {
    let cancelled = false;
    async function run(): Promise<void> {
      setLoading(true);
      setError(null);
      try {
        const path = buildQueryPath("/api/v1/lsp/loan-applications", {
          offset: 0,
          limit: 50,
          paginationDetails: "true",
        });
        const payload = await requestJson<LspLoanRow[] | BackendResponse>(path);
        const rows = Array.isArray(payload) ? payload : (payload.items ?? []);
        if (!cancelled) setItems(rows);
      } catch (err) {
        if (cancelled) return;
        const message =
          err instanceof ApiError
            ? err.status === 401 || err.status === 403
              ? "Your role cannot list loans for this LSP."
              : err.message
            : err instanceof Error
              ? err.message
              : "Failed to load loans.";
        setError(message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void run();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        eyebrow="LSP workspace"
        title="My loans"
        description="Active loans and applications scoped to your LSP."
      />
      {loading ? (
        <div
          role="status"
          className="text-foreground-muted flex items-center gap-2 text-sm"
        >
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading loans…
        </div>
      ) : error ? (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border px-4 py-3 text-sm"
        >
          {error}
        </div>
      ) : !items || items.length === 0 ? (
        <EmptyState
          icon={Folder}
          title="No loans yet"
          description="When your LSP originates loans through the LMS, they will appear here."
        />
      ) : (
        <div className="border-border overflow-hidden rounded-md border">
          <table className="w-full text-sm">
            <thead className="bg-muted/30 text-foreground-muted text-left text-xs">
              <tr>
                <th className="px-4 py-2 font-medium">External ID</th>
                <th className="px-4 py-2 font-medium">Borrower</th>
                <th className="px-4 py-2 font-medium">Product</th>
                <th className="px-4 py-2 font-medium tabular-nums">Amount</th>
                <th className="px-4 py-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => (
                <tr key={row.id} className="border-border/40 border-t">
                  <td className="px-4 py-2 font-mono text-xs">
                    <Link
                      to={`/my-loans/${row.id}`}
                      className="text-brand-700 hover:underline"
                    >
                      {row.externalLoanId ?? row.id.slice(0, 8)}
                    </Link>
                  </td>
                  <td className="px-4 py-2">{row.borrowerFullName}</td>
                  <td className="px-4 py-2">{row.productName}</td>
                  <td className="px-4 py-2 tabular-nums">
                    {formatINR(Number(row.requestedAmount ?? 0))}
                  </td>
                  <td className="px-4 py-2">{row.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default MyLoansPage;
export const Component = MyLoansPage;
