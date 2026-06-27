# Bhawana LMS

A multi-tenant loan management system. Lending Service Providers (LSPs) originate loans via API; an internal rule engine approves them; the platform disburses funds to borrowers and collects repayment, operating each LSP's own bank accounts through a bank integration.

## Language

### LSP bank accounts

Each LSP operates its **own** pair of bank accounts at the bank; the platform acts on the accounts belonging to the LSP that owns the loan, never on a single shared lender pool.

**Disbursal account**:
The per-LSP bank account that outgoing disbursements are debited from. The debit leg of a disbursement for one of LSP A's loans draws on LSP A's disbursal account.
_Avoid_: pool account, source account, lender account

**Collection account**:
The per-LSP bank account that incoming borrower repayments are credited to. Symmetric to the disbursal account, on the repayment side.
_Avoid_: receivables account, repayment account

### Disbursement

**Disbursement**:
The movement of the net loan amount from the LSP's disbursal account to the borrower through the bank. A disbursement decomposes into two legs that settle independently, and is only complete — the loan only reaches `DISBURSED` — once all three bank calls have returned success: the composite disburse call, the debit status check, and the credit status check.
_Avoid_: payout, transfer, payment

**Disbursement attempt**:
A single try at moving the money for a loan. Holds the live state of its three checkpoints (composite disburse, debit status, credit status), the idempotency key used with the bank, and a snapshot of the disbursal account it draws on. A loan account may have several attempts over time, but a new attempt is created only after a prior one's funds are confirmed returned to the LSP's disbursal account — never as a re-initiation past the point of no return.
_Avoid_: retry, disbursement request

**Disbursement log**:
The append-only record of every individual call made to the bank (the composite disburse call and each status poll), one immutable row per call. It is evidence, never state; it is never overwritten.
_Avoid_: disbursement record, request log (when meaning the mutable state)

**Debit leg**:
The stage where funds leave the LSP's disbursal account at the bank. Confirmed by the bank's debit status check.
_Avoid_: withdrawal, source debit

**Credit leg**:
The stage where funds land in the borrower's bank account. Confirmed by the bank's credit status check, which is eventually-consistent — it may stay pending across multiple polls before resolving.
_Avoid_: payout, beneficiary transfer

**Point of no return**:
Once the debit leg succeeds, the disbursement must never be re-initiated — a second initiation would debit the LSP's disbursal account twice. The only forward path is reconciliation.

**Reconciliation**:
Repeatedly polling the bank's status checks for an in-flight disbursement until it reaches a terminal outcome — credited to the borrower, or returned to the LSP's disbursal account. On a failed or pending credit, the bank's prescribed action is to poll the status check again, not to re-initiate.

**In flight**:
The window in which a disbursement attempt has been initiated but not yet resolved to a terminal outcome. While in flight, the money may have left the LSP's disbursal account, so the loan is hands-off for ops — no manual status changes, no second initiation. Leg-level detail of an in-flight attempt lives only on the attempt; the account simply reads as awaiting its verdict.

## Example dialogue

> **Dev:** The credit failed — should the worker retry the disbursement?
> **Domain expert:** No. If the debit succeeded, we're past the point of no return. We don't re-initiate; we keep polling the credit status check until the bank tells us it either credited the borrower or returned the money to the LSP's disbursal account. Only after a confirmed return is a fresh disbursement safe.
