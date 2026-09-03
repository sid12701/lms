# Bhavana LMS Platform Defense Cheat Sheet

Use this after reading `BHAVANA-PLATFORM-DECISION-HANDBOOK.md`.

> **Remediation update — 2026-07-24:** Concurrent one-open-loan enforcement
> (H-01) is implemented in the current worktree. References below to that gap
> describe the audited 2026-07-23 baseline.

## The 30-second explanation

Bhavana LMS is a partner-operated, multi-tenant lending platform. LSPs originate
through APIs, while Bhavana owns borrower identity, credit rules, product terms,
loan state, disbursement orchestration, servicing, evidence, and partner
notifications.

It is a modular monolith because approval, payout, repayment, schedules, audit,
and notifications share strong transactional invariants. The system keeps
authoritative operational state in PostgreSQL and performs external work from
durable records using idempotency, an outbox, leased workers, and a disbursement
intent.

The foundation is defensible. Synthetic UAT is supported. Live money still
requires atomic open-loan enforcement, real ICICI integration, mock/live
isolation, maker-checker, beneficiary control, financial ledger/reconciliation,
privacy controls, and production operations evidence.

## The two-minute architecture explanation

1. **Partner and product control:** LSPs, API clients, product versions, and
   partner-product mappings decide who may originate what.
2. **Tenant control:** LSP scope comes from authentication; PostgreSQL RLS is the
   database backstop.
3. **Origination:** An idempotent LSP request resolves a global borrower,
   snapshots a product version, records audit/checklist state, and emits a
   durable partner event.
4. **Decisioning:** Eight deterministic rules automatically approve or reject.
   Approval creates the loan account and schedule.
5. **Disbursement:** A durable intent and deterministic reference are committed
   before the provider call. Ambiguous results are inquired/reconciled, not
   blindly resent.
6. **Servicing:** Installments are persisted and frozen after payout. Current
   repayment accepts one exact installment with database locking and
   idempotency.
7. **Operations/integration:** State changes create audit evidence and webhook
   outbox rows. Workers deliver, retry, dead-letter, and redrive.

## The ten business decisions

| ID  | Business decision              | Architectural consequence                                 |
| --- | ------------------------------ | --------------------------------------------------------- |
| D1  | API-only origination           | LSP create API is the sole authoritative intake path      |
| D2  | Automated credit               | Deterministic rule engine; no normal underwriter queue    |
| D3  | One global borrower/open loan  | Global PAN identity and cross-LSP exposure check          |
| D4  | LSP deactivation kills access  | Status, clients, tokens, mappings, and gates act together |
| D5  | Fee withheld from payout       | Gross account/schedule, net cash disbursal                |
| D6  | Full-installment repayment     | Simple exact-EMI posting; no general allocator yet        |
| D7  | Schedule freezes after payout  | Historical terms cannot be silently mutated               |
| D8  | Tenant isolation               | Principal-derived scope plus PostgreSQL RLS               |
| D9  | Material actions are auditable | Transition/audit/outbox evidence in business workflows    |
| D10 | No borrower portal             | Internal and LSP identities/surfaces only                 |

## The strongest choices

### Modular monolith

**Why:** Core lending changes are transactionally coupled.

**Defense:** One transaction is safer than distributed compensation at current
scale and team structure.

**Reconsider when:** A capability has proven independent scale, availability,
ownership, or regulatory needs.

### Global borrower plus tenant relationships

**Why:** Bhavana owns consolidated credit exposure across distribution partners.

**Defense:** Identity and exposure are lender-global; visibility remains
partner-scoped through relationships and RLS.

**Improve:** Serialize approval on the borrower and enforce D3 atomically.

### Immutable product versions

**Why:** Future pricing changes must not rewrite old contracts.

**Defense:** Product identity may evolve; accepted loan economics may not.

### Explicit state machines

**Why:** Status determines legal business actions and money movement.

**Defense:** Transitions are readable, testable, audited, and protected from
arbitrary state assignment.

### Transactional webhook outbox

**Why:** A database commit and partner HTTP call cannot be one atomic operation.

**Defense:** Persist the event with the business change, then deliver with
bounded retries and evidence.

### Durable disbursement intent

**Why:** A bank timeout can mean money moved even when no response arrived.

**Defense:** Persist one reference before sending and inquire on ambiguity rather
than paying twice.

### PostgreSQL RLS

**Why:** Partner isolation is too important to rely on every query author.

**Defense:** Authentication, tenant context, and database policies are separate
layers.

## The alternatives in one view

| Current choice        | Main alternative              | Why current is preferred now                                    | Switch when                                          |
| --------------------- | ----------------------------- | --------------------------------------------------------------- | ---------------------------------------------------- |
| Modular monolith      | Microservices                 | Preserves transactional invariants and lowers operations burden | Independent boundary is measured                     |
| Synchronous intake    | Async job/event intake        | Local checks give immediate deterministic result                | Heavy external verification or high burst volume     |
| Global borrower       | Tenant-local identity         | Preserves lender-wide exposure                                  | Tenants are legally independent lenders              |
| Rules in code         | Rules/decision engine         | Eight simple rules remain explainable and testable              | Policy owners need independent changes/models        |
| Product versions      | Mutable product               | Protects historical contract terms                              | Do not switch                                        |
| Shared schema + RLS   | DB per tenant                 | Supports global borrower and simpler operations                 | Legal/infrastructure isolation outweighs global view |
| DB outbox             | Direct webhook/broker publish | Prevents commit/publish gaps                                    | CDC may transport the outbox later                   |
| Durable payout intent | Synchronous provider call     | Prevents duplicate payout under ambiguity                       | Do not switch for live money                         |
| Exact-EMI projection  | Receipt/journal               | Current is a safe narrow UAT slice                              | Add receipt/journal before real collections          |
| Operational reports   | Replica/CDC warehouse         | Proportionate at current volume                                 | Analytics affects primary or consumers multiply      |

## Known gaps you should volunteer

Do not wait for reviewers to discover these:

1. Concurrent approvals can violate one-open-loan D3.
2. Mock and live disbursement are not mutually exclusive.
3. Intent workflow can be disabled into a legacy inline provider call.
4. No maker-checker or signed STP limits exist.
5. Beneficiary is frozen at intent creation, not approval.
6. Real ICICI integration and bank reconciliation do not exist.
7. Current repayment is not a receipt/reversal/accounting ledger.
8. PAN/PII masking, encryption, and retention are incomplete.
9. Document upload does not equal KYC verification or malware clearance.
10. Production PostgreSQL, capacity, DR, and failover evidence remains incomplete.

## How to frame those gaps

> These are not hidden unknowns. They define the boundary between synthetic UAT,
> partner pilot, live payout, and authoritative collections. The core has the
> right seams for incremental hardening.

Avoid:

> It is production ready except for a few minor things.

## Common architecture-review questions

### Why not microservices?

The system's hardest problems are consistency and money safety, not independent
service scaling. Splitting application, account, schedule, payment, and audit
would replace local transactions with distributed failure modes. Extract only a
boundary that has a measured independent requirement.

### Why not Kafka?

The outbox already solves reliable business-event persistence. Kafka becomes
useful when multiple high-volume independent consumers need a shared stream. It
does not remove the need to transactionally bridge database state and event
publication.

### Why not event sourcing?

Most platform data needs current relational state and constraints. Financial
accounting needs an immutable journal, but that does not require event-sourcing
products, users, documents, and every workflow.

### Why keep rules in Java?

The rules are few, deterministic, reviewed, version-controlled, and tested.
Introducing a rules runtime would add another programming model without solving
a current ownership or complexity problem.

### Why use a global borrower in a multi-tenant platform?

Bhavana is the lender and needs consolidated exposure. LSPs are tenant-scoped
distribution relationships, not separate lenders. RLS controls visibility while
the global identity controls risk.

### Why store a repayment schedule?

It is the contractual servicing projection. Persisting it makes terms,
collection behavior, DPD, and disputes reproducible. It freezes after money
moves.

### Why is repayment so limited?

Full-installment posting was a deliberate low-risk slice for UAT. The production
evolution adds immutable receipt, allocation, suspense, journal, reversal, and
reconciliation while keeping the installment projection.

### Is KYC implemented?

Required-document presence and secure storage are implemented. Full document
verification, malware quarantine, CKYC submission, and production compliance
controls are not.

### Is ICICI integrated?

The safe payout state machine, adapter seam, mock behavior, durable intent, and
status model exist. The production ICICI HTTP/crypto/error/reconciliation
adapter does not.

## What should be built next

### Before partner pilot

- Atomic D3 enforcement.
- PAN and name masking corrections.
- Dashboard semantic corrections.
- Cancellation-state correction.
- Required idempotency for privileged/money commands.
- Hermetic backend tests and PostgreSQL CI.

### Before live payout

- Exclusive provider modes and startup guards.
- Mandatory intent workflow.
- Real ICICI adapter and status inquiry.
- Maker-checker/STP caps.
- Approval-time beneficiary snapshot/reaffirmation.
- Bank reconciliation and operational drills.
- PII/document security controls.

### Before authoritative collections

- Receipt ingestion.
- Allocation and suspense.
- Immutable journal.
- Bounce/reversal/correction.
- Account reopen rules.
- Three-way reconciliation in shadow mode.
- Finance-approved accounting policy.

## The final position

Defend:

- the domain model,
- the transactional foundation,
- the modular-monolith topology,
- immutable contractual snapshots,
- principal-derived tenancy and RLS,
- idempotency,
- the outbox,
- the durable intent,
- and incremental hardening.

Do not defend:

- missing controls as unnecessary,
- target-state specs as implemented,
- mock behavior as production capability,
- or the exact-EMI model as a financial ledger.

The most accurate closing statement is:

> The platform has a sound architecture for a lender-controlled, multi-partner
> LMS. Its current implementation is a strong synthetic-UAT baseline. Broader
> regulated use should proceed by hardening known invariants and controls around
> the existing architecture, not by rewriting it.
