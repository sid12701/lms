/**
 * Per-tab API client for the borrower-detail surface (Phase 6).
 *
 * Mirrors the contract in `./types.ts`. Like the loan-applications
 * `api-tabs.ts`, this module owns shape + transport only; the handler
 * itself lives in `@/mocks/api/borrowers.ts` (agent A).
 *
 * Runtime parsers are intentionally permissive on the nested rows — the
 * mock handler already validated each row when the DB was seeded; we
 * only assert the top-level wrapper shape so callers get a typed Promise.
 */
import { z } from "zod";
import { dispatch } from "@/mocks/router";
import type { BorrowerActivityResponse, BorrowerLoansResponse } from "./types";

const Permissive = z.unknown();

const BorrowerLoansResponseSchema: z.ZodType<BorrowerLoansResponse> = z.object({
  loans: z.array(Permissive).readonly(),
}) as unknown as z.ZodType<BorrowerLoansResponse>;

const BorrowerActivityResponseSchema: z.ZodType<BorrowerActivityResponse> = z.object({
  entries: z.array(Permissive).readonly(),
}) as unknown as z.ZodType<BorrowerActivityResponse>;

/** GET `/api/v1/borrowers/:id/loans` — Loans tab data. */
export async function fetchBorrowerLoans(id: string): Promise<BorrowerLoansResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${id}/loans`,
    },
    BorrowerLoansResponseSchema,
  );
}

/** GET `/api/v1/borrowers/:id/activity` — Activity tab data. */
export async function fetchBorrowerActivity(id: string): Promise<BorrowerActivityResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${id}/activity`,
    },
    BorrowerActivityResponseSchema,
  );
}
