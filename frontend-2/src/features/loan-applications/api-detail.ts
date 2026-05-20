/**
 * Loan-application detail API client — thin wrapper around the mock router
 * for the Phase 5 detail surface.
 *
 * The contract is owned by `./types.ts`; this module registers permissive
 * Zod parsers that mirror the response shapes so the router's drift
 * detection fires when agent A's handlers return something unexpected.
 * The wrappers only own shape + transport — actual business logic lives
 * in `@/mocks/api/loan-applications.ts`.
 */
import { z } from "zod";
import { dispatch } from "@/mocks/router";
import { newIdempotencyKey } from "@/lib/idempotency";
import { LoanStatus } from "@/schemas/loan-application";
import type {
  InitiateDisbursementInput,
  LoanApplicationActivityResponse,
  LoanApplicationDetail,
  LoanApplicationWebhooksResponse,
  TransitionStatusInput,
} from "./types";
import type { ApplicationAuditEvent, LoanApplication } from "@/types";

// ─── Runtime parsers ─────────────────────────────────────────────────────────
//
// The detail payload includes deep nested domain objects (Borrower, Lsp,
// LoanProduct, LoanAccount) which already passed Zod when they were seeded
// upstream. We assert the top-level shape only and let TypeScript narrow
// the nested fields — mirrors the pattern in `mocks/api/loan-applications.ts`.
const Permissive = z.unknown();

export const LoanApplicationDetailSchema = z.object({
  application: Permissive,
  borrower: Permissive,
  lsp: Permissive,
  product: Permissive,
  account: Permissive.nullable(),
  docsComplete: z.boolean(),
  scheduleValid: z.boolean(),
}) as unknown as z.ZodType<LoanApplicationDetail>;

const ApplicationAuditEventSchema = z.object({
  id: z.string().min(1),
  applicationId: z.string().min(1),
  fromStatus: LoanStatus.nullable(),
  toStatus: LoanStatus,
  action: z.string().min(1),
  actorId: z.string().min(1),
  actorRole: z.string().min(1),
  channel: z.string().min(1),
  correlationId: z.string().min(1),
  reason: z.string().nullable(),
  contextJson: z.record(z.unknown()).optional(),
  createdAt: z.string().min(1),
}) as unknown as z.ZodType<ApplicationAuditEvent>;

export const LoanApplicationActivityResponseSchema: z.ZodType<LoanApplicationActivityResponse> =
  z.object({
    events: z.array(ApplicationAuditEventSchema).readonly(),
  });

const WebhookDeliverySchema = z.object({
  id: z.string().min(1),
  eventType: z.string().min(1),
  endpoint: z.string().min(1),
  status: z.enum(["PENDING", "DELIVERED", "FAILED", "DEAD_LETTERED"]),
  attemptCount: z.number().int().nonnegative(),
  lastAttemptAt: z.string().nullable(),
  lastError: z.string().nullable(),
  createdAt: z.string().min(1),
});

export const LoanApplicationWebhooksResponseSchema: z.ZodType<LoanApplicationWebhooksResponse> =
  z.object({
    deliveries: z.array(WebhookDeliverySchema).readonly(),
  });

const ApplicationLikeSchema = Permissive as unknown as z.ZodType<LoanApplication>;

export const TransitionResponseSchema = z.object({
  application: ApplicationLikeSchema,
  event: ApplicationAuditEventSchema,
});
export type TransitionResponse = z.infer<typeof TransitionResponseSchema>;

export const DisbursementResponseSchema = z.object({
  application: ApplicationLikeSchema,
  events: z.array(ApplicationAuditEventSchema).readonly(),
});
export type DisbursementResponse = z.infer<typeof DisbursementResponseSchema>;

// ─── Public surface ──────────────────────────────────────────────────────────

/** Fetch the full detail payload for one loan application. */
export async function fetchLoanApplicationDetail(
  id: string,
): Promise<LoanApplicationDetail> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}`,
    },
    LoanApplicationDetailSchema,
  );
}

/** Fetch the per-application audit timeline. */
export async function fetchLoanApplicationActivity(
  id: string,
): Promise<LoanApplicationActivityResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}/activity`,
    },
    LoanApplicationActivityResponseSchema,
  );
}

/** Fetch the per-application webhook-delivery feed. */
export async function fetchLoanApplicationWebhooks(
  id: string,
): Promise<LoanApplicationWebhooksResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}/webhooks`,
    },
    LoanApplicationWebhooksResponseSchema,
  );
}

/**
 * Post a lifecycle transition. The `idempotencyKey` on the input must be a
 * non-empty string (minted by `ActionBar`/`TransitionConfirmDialog`); we
 * forward it both in the body and in the `Idempotency-Key` header so the
 * router's BR-5 dedupe cache catches duplicate submits.
 */
export async function postTransition(
  id: string,
  input: TransitionStatusInput,
): Promise<TransitionResponse> {
  const idempotencyKey =
    typeof input.idempotencyKey === "string" && input.idempotencyKey.length > 0
      ? input.idempotencyKey
      : newIdempotencyKey();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}/transitions`,
      body: { ...input, idempotencyKey },
      headers: { "Idempotency-Key": idempotencyKey },
    },
    TransitionResponseSchema,
  );
}

/** Initiate disbursement. Same idempotency-key contract as `postTransition`. */
export async function postDisbursement(
  id: string,
  input: InitiateDisbursementInput,
): Promise<DisbursementResponse> {
  const idempotencyKey =
    typeof input.idempotencyKey === "string" && input.idempotencyKey.length > 0
      ? input.idempotencyKey
      : newIdempotencyKey();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/loan-applications/${encodeURIComponent(id)}/disbursement`,
      body: { ...input, idempotencyKey },
      headers: { "Idempotency-Key": idempotencyKey },
    },
    DisbursementResponseSchema,
  );
}
