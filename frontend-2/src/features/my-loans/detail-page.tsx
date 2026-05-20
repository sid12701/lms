import { useParams } from "react-router-dom";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { Folder } from "lucide-react";

export function MyLoanDetailPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        eyebrow="LSP workspace"
        title={id ? `Loan ${id}` : "Loan"}
        description="Status, schedule, and repayments for the selected loan."
      />
      <EmptyState
        icon={Folder}
        title="Coming in Phase 6"
        description="The LSP loan detail surface lands in Phase 6."
      />
    </div>
  );
}

export default MyLoanDetailPage;
export const Component = MyLoanDetailPage;
