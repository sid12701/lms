import { PRODUCT_ORGANIZATION_NAME } from "@/lib/product-branding";
import { shortId } from "@/lib/short-id";

/** Default browser tab title when no page sets a dynamic label. */
export const DEFAULT_DOCUMENT_TITLE = PRODUCT_ORGANIZATION_NAME;

export interface LoanPageIdentityInput {
  applicationId: string;
  externalLoanId?: string | null;
  borrowerName?: string | null;
}

/**
 * Pick the most useful loan label for breadcrumbs, h1 context, and tab titles.
 * Prefers the LSP-facing id, then borrower name, then a short internal id.
 */
export function resolveLoanPageIdentity({
  applicationId,
  externalLoanId,
  borrowerName,
}: LoanPageIdentityInput): string {
  const external = externalLoanId?.trim();
  if (external) return external;
  const borrower = borrowerName?.trim();
  if (borrower) return borrower;
  return shortId(applicationId);
}

/** `{identity} · Bhawana Capital` for `document.title`. */
export function formatLoanDocumentTitle(identity: string): string {
  return `${identity} · ${PRODUCT_ORGANIZATION_NAME}`;
}
