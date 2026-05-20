/**
 * Borrower-detail API client — thin transport wrapper around the mock
 * router for the Phase 6 borrower-detail surface.
 *
 * The contract is owned by `./types.ts`; this module registers permissive
 * Zod parsers that mirror the response shapes so the router's drift
 * detection fires when the upstream mock handler (`mocks/api/borrowers.ts`)
 * returns something unexpected.
 *
 * BR-5: `recordPiiReveal` always forwards the supplied idempotency key in
 * both the request body and the `Idempotency-Key` header so the router's
 * 30s dedupe cache catches duplicate submits.
 */
import { z } from "zod";
import { dispatch } from "@/mocks/router";
import { newIdempotencyKey } from "@/lib/idempotency";
import { PiiFieldName } from "@/schemas/audit";
import type {
  BorrowerDetail,
  RecordPiiRevealInput,
  RecordPiiRevealResponse,
} from "./types";

// ─── Runtime parsers ─────────────────────────────────────────────────────────
//
// The detail payload includes a deeply-nested `Borrower` which already
// passed Zod when seeded upstream. We assert the top-level shape only and
// let TypeScript narrow nested fields — mirrors the Phase 5 pattern.

const Permissive = z.unknown();

const VisibleLspSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
});

export const BorrowerDetailSchema = z.object({
  borrower: Permissive,
  visibleLsps: z.array(VisibleLspSchema).readonly(),
  totals: z.object({
    openApplicationsCount: z.number().int().nonnegative(),
    closedApplicationsCount: z.number().int().nonnegative(),
    lifetimeDisbursedAmount: z.number().nonnegative(),
    activeOverdueAmount: z.number().nonnegative(),
  }),
}) as unknown as z.ZodType<BorrowerDetail>;

export const RecordPiiRevealResponseSchema: z.ZodType<RecordPiiRevealResponse> =
  z.object({
    value: z.string(),
    auditId: z.string().min(1),
  });

// ─── Public surface ──────────────────────────────────────────────────────────

/** Fetch the joined borrower-detail payload for `/borrowers/:id`. */
export async function fetchBorrowerDetail(id: string): Promise<BorrowerDetail> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${encodeURIComponent(id)}`,
    },
    BorrowerDetailSchema,
  );
}

/**
 * Append a PII-reveal audit row (BR-7) and return the unmasked value.
 *
 * The caller is responsible for minting the idempotency key (a fresh one
 * per attempt — `useRecordPiiReveal` does this) but we defensively fall
 * back to `newIdempotencyKey()` when the input is empty so the BR-5 cache
 * always has a key to work with.
 */
export async function recordPiiReveal(
  input: RecordPiiRevealInput,
): Promise<RecordPiiRevealResponse> {
  // Validate `field` value at runtime so a typo in the caller surfaces here
  // rather than as an opaque 400 from the mock handler.
  PiiFieldName.parse(input.field);

  const idempotencyKey =
    typeof input.idempotencyKey === "string" && input.idempotencyKey.length > 0
      ? input.idempotencyKey
      : newIdempotencyKey();

  return dispatch(
    {
      method: "POST",
      path: `/api/v1/borrowers/${encodeURIComponent(input.borrowerId)}/pii-reveal`,
      body: {
        borrowerId: input.borrowerId,
        field: input.field,
        reason: input.reason,
        idempotencyKey,
      },
      headers: { "Idempotency-Key": idempotencyKey },
    },
    RecordPiiRevealResponseSchema,
  );
}
