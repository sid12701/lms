import { z } from "zod";
// Value import: the Zod schema itself, not just the inferred type.
import { LoanStatus } from "@/schemas/loan-application";

/**
 * URL-bound filters for `/my-loans`.
 *
 * `status` is a single value rather than an array, because that is what the LSP
 * list endpoint accepts. Modelling it as an array "for future multi-select"
 * is what left `/loan-applications` with a schema, a control and an endpoint
 * that disagreed — the shape here matches the wire.
 */
export const MyLoanListFilters = z.object({
  /** Free-text borrower / reference search. */
  q: z.string().trim().min(1).max(120).optional(),
  status: LoanStatus.optional(),
  /** Page index, zero-based. */
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});
export type MyLoanListFilters = z.infer<typeof MyLoanListFilters>;
