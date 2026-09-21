import type { components } from "@/lib/api/generated/schema";

/** Ops loan-application detail payload from OpenAPI (`LoanApplicationOpsController`). */
export type OpsLoanApplicationDetailResponse =
  components["schemas"]["LoanApplicationDetailResponse"];

export type OpsLoanApplicationDocumentChecklistResponse =
  components["schemas"]["LoanApplicationDocumentChecklistResponse"];

/** Ops audit-timeline row (`LoanApplicationOpsController` audit-events). */
export type OpsLoanApplicationAuditEventResponse =
  components["schemas"]["LoanApplicationAuditEventResponse"];

/** Ops foreclosure-quote payload (`LoanApplicationOpsController`). */
export type OpsLoanForeclosureQuoteResponse = components["schemas"]["LoanForeclosureQuoteResponse"];

/** Ops durable disbursement reference (`LoanApplicationOpsController`). */
export type OpsDisbursementReferenceResponse =
  components["schemas"]["DisbursementReferenceResponse"];
