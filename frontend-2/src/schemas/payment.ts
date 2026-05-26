/**
 * Payment transactions + allocation.
 *
 * Allocation order per blueprint §13 waterfall: penalty → fees → interest → principal.
 * Field naming preserves that order for downstream verification by tests.
 *
 * BR-13: a "standard" repayment posting only accepts the FULL outstanding
 * amount of the next installment — enforced by the mock router using the
 * single-installment-allocation invariant below.
 */
import { z } from "zod";
import { IdempotencyKey, Iso8601, MoneyINR, MoneyINRPositive, Uuid } from "./common";

export const PaymentChannel = z.enum(["BANK_TRANSFER", "UPI", "CASH", "ADJUSTMENT"]);
export type PaymentChannel = z.infer<typeof PaymentChannel>;

export const PaymentAllocation = z.object({
  installmentId: Uuid,
  /** Order matches blueprint §13 waterfall. */
  penalty: MoneyINR,
  fees: MoneyINR,
  interest: MoneyINR,
  principal: MoneyINR,
});
export type PaymentAllocation = z.infer<typeof PaymentAllocation>;

export const PaymentTransaction = z.object({
  id: Uuid,
  accountId: Uuid,
  /** Null for free-form adjustments not bound to a specific installment. */
  installmentId: Uuid.nullable(),
  channel: PaymentChannel,
  amount: MoneyINRPositive,
  postedAt: Iso8601,
  postedBy: Uuid,
  /** External bank/UPI/cheque reference supplied by the posting channel. */
  reference: z.string().trim().min(1).nullable().optional(),
  /** BR-5: idempotency key on every mutation. */
  idempotencyKey: IdempotencyKey,
  allocation: z.array(PaymentAllocation),
});
export type PaymentTransaction = z.infer<typeof PaymentTransaction>;
