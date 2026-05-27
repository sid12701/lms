/**
 * Contract for the borrowers directory list (`/borrowers`).
 *
 * Mirrors the backend's `BorrowerSummaryResponse` shape on the wire and
 * adds the page-side filter envelope. `aadharNumberMasked` is the only
 * PII we surface on the list and it is always pre-masked by the backend
 * (see `BorrowerAdminController.maskAadhar`).
 */
import { z } from "zod";

export const BorrowerListFilters = z.object({
  /** Case-insensitive substring across name / PAN / mobile / email. */
  q: z.string().trim().min(1).max(120).optional(),
  /** Page index, zero-based. Defaults to 0. */
  page: z.coerce.number().int().min(0).optional(),
  /** Page size. Defaults to 25. */
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});
export type BorrowerListFilters = z.infer<typeof BorrowerListFilters>;

export interface BorrowerSummary {
  id: string;
  fullName: string;
  pan: string;
  mobile: string;
  email: string | null;
  city: string | null;
  state: string | null;
  /** Always masked on the wire — never holds the cleartext aadhar. */
  aadharNumberMasked: string | null;
  visibleLspIds: readonly string[];
}

export interface BorrowerListResponse {
  items: readonly BorrowerSummary[];
  total: number;
  page: number;
  pageSize: number;
}
