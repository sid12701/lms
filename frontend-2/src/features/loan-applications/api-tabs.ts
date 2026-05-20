/**
 * Per-tab API client for the loan-application detail surface.
 *
 * Mirrors the contract in `./types.ts`. Like `./api.ts`, this module owns
 * shape + transport only — the handler lives at
 * `@/mocks/api/loan-applications.ts` (agent A).
 *
 * Runtime parsers are intentionally permissive on the nested rows (the
 * mock handler already validated them when they were seeded) — we assert
 * the top-level wrapper shape so callers get a typed Promise.
 */
import { z } from "zod";
import { dispatch } from "@/mocks/router";
import type {
  LoanApplicationDocumentsResponse,
  LoanApplicationRepaymentsResponse,
  LoanApplicationScheduleResponse,
  PostRepaymentInput,
} from "./types";

const Permissive = z.unknown();

const ScheduleResponseSchema: z.ZodType<LoanApplicationScheduleResponse> = z.object({
  schedule: Permissive.nullable(),
  installments: z.array(Permissive).readonly(),
}) as unknown as z.ZodType<LoanApplicationScheduleResponse>;

const DocumentsResponseSchema: z.ZodType<LoanApplicationDocumentsResponse> = z.object({
  documents: z.array(Permissive).readonly(),
}) as unknown as z.ZodType<LoanApplicationDocumentsResponse>;

const RepaymentsResponseSchema: z.ZodType<LoanApplicationRepaymentsResponse> = z.object({
  payments: z.array(Permissive).readonly(),
}) as unknown as z.ZodType<LoanApplicationRepaymentsResponse>;

/** Drift-detection parser for `postRepayment` — we don't read the response. */
const PostRepaymentResponseSchema = z
  .object({
    payment: Permissive,
    application: Permissive,
    autoAdvanced: z.boolean().optional(),
    autoClosed: z.boolean().optional(),
  })
  .passthrough();

/** GET `/api/v1/loan-applications/:id/schedule` — Schedule tab data. */
export async function fetchLoanApplicationSchedule(
  id: string,
): Promise<LoanApplicationScheduleResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}/schedule`,
    },
    ScheduleResponseSchema,
  );
}

/** GET `/api/v1/loan-applications/:id/documents` — Documents tab data. */
export async function fetchLoanApplicationDocuments(
  id: string,
): Promise<LoanApplicationDocumentsResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}/documents`,
    },
    DocumentsResponseSchema,
  );
}

/** GET `/api/v1/loan-applications/:id/repayments` — Repayments tab data. */
export async function fetchLoanApplicationRepayments(
  id: string,
): Promise<LoanApplicationRepaymentsResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}/repayments`,
    },
    RepaymentsResponseSchema,
  );
}

/**
 * POST `/api/v1/loan-applications/:id/repayments` — post a repayment.
 *
 * The caller is expected to mint a fresh BR-5 idempotency key (the
 * `RepaymentPostDialog` does this via `newIdempotencyKey()`); we forward
 * it both as a header (router cache key) and inside the body (handler
 * persists it on the `PaymentTransaction`).
 *
 * Returns `void` per the agent brief — the mutation hook invalidates the
 * relevant query keys; consumers don't need the server's echo response.
 */
export async function postRepayment(id: string, input: PostRepaymentInput): Promise<void> {
  await dispatch(
    {
      method: "POST",
      path: `/api/v1/loan-applications/${id}/repayments`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    PostRepaymentResponseSchema,
  );
}
