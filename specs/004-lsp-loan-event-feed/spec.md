# Spec: LSP loan event feed (pull-based partner lifecycle updates)

**Status:** ready-for-agent
**Source decision:** ADR 0007 — Partner lifecycle updates are pull-based; the webhook delivery path is removed
**Related:** ADR 0003 (loan origination is API-only), ADR 0005 (tenant scope from principal, fail-closed)
**Glossary:** `CONTEXT.md` § Partner lifecycle updates — *loan event log*, *cursor*, *feed order*, *internal history*

> Note: `specs/003-lsp-event-payload/` is a completed ADR wording edit only. It changed one paragraph of
> ADR 0007 and no runtime code. This spec is the build.

## Problem Statement

LSPs need to learn about loan lifecycle changes — approval verdicts, disbursement outcomes, delinquency,
repayment, closure — so they can update their own systems and tell their borrowers what happened.

Today the platform pushes those changes as webhooks. That makes the platform responsible for something it
cannot control or prove:

- **Delivery depends on the partner, but failures arrive as platform tickets.** Each partner's uptime, TLS,
  DNS, rate limits and handler correctness sit on the critical path. A partner outage becomes platform queue
  depth. An HTTP 200 means "received", never "applied", so the strongest guarantee the platform can offer is
  weaker than the guarantee partners actually need.
- **The dispatcher cannot carry the planned volume.** A 60-second fixed delay against a batch of 20 is
  0.33 events/second per pod, against a requirement of roughly 10–25 events/second at 100K originations/day.
- **One dead partner starves every other partner.** The dispatch claim orders globally by creation time with
  no per-tenant fairness, so a single LSP with a dead endpoint fills every batch with its own retries —
  cross-tenant head-of-line blocking.
- **History cannot be replayed or backfilled.** Events are filtered by subscription *at write time*, so the
  outbox records what the configuration allowed rather than what actually happened. An LSP that enables a new
  event type can never see what it missed.

From an LSP's perspective the symptom is simple: if they miss an update, there is no way to go and get it.

## Solution

**LSPs pull lifecycle changes from an append-only loan event log. The platform does not push.**

Every loan lifecycle fact is written to the loan event log in the same transaction as the state change it
describes — unconditionally, with no subscription and no enable flag. Every LSP has a feed from the moment it
exists. LSPs read their feed from a single endpoint, passing a cursor to resume where they left off, and the
platform tells them whether more events are waiting.

The partner owns consumption; the platform owns availability. An LSP that goes down for a day comes back and
reads forward from its cursor. An LSP that widens its event-type filter rewinds its cursor and receives
everything it previously filtered out.

The webhook delivery machinery is deleted outright, not left dormant behind a flag.

## User Stories

### Consuming the feed

1. As an LSP integrator, I want to fetch loan events from a single endpoint, so that I do not have to operate
   a publicly reachable, always-up HTTPS receiver just to learn about my own loans.
2. As an LSP integrator, I want to pass a cursor naming the last event I consumed, so that I resume exactly
   where I left off instead of re-reading the whole feed.
3. As an LSP integrator, I want to call the feed with no cursor, so that I can start consuming from the
   beginning of the retained window on first integration.
4. As an LSP integrator, I want each response to tell me whether more events are waiting, so that I know
   whether to poll again immediately or wait for my normal interval.
5. As an LSP integrator, I want to cap how many events come back in one call, so that I can size responses to
   what my consumer can process in one batch.
6. As an LSP integrator, I want a sensible default and a hard maximum on that cap, so that I cannot
   accidentally ask for a response large enough to hurt either side.
7. As an LSP integrator, I want to filter the feed to only the event types I care about, so that I do not
   have to parse and discard events my integration ignores.
8. As an LSP integrator, I want my cursor to be over the unfiltered stream, so that widening my filter later
   lets me rewind and receive events I previously skipped.
9. As an LSP integrator, I want the cursor to be an opaque token, so that I am not tempted to build logic on
   its internal structure and break when the platform changes it.
10. As an LSP integrator, I want to poll on a schedule I choose, so that my consumption rate is my own
    operational decision rather than something the platform imposes.

### Trusting the feed

11. As an LSP integrator, I want events for a single loan to arrive in the order they happened, so that I
    never apply a disbursement outcome before the approval that authorised it.
12. As an LSP integrator, I want the platform to state plainly that ordering across unrelated loans is a
    stable replay order and not a causal one, so that I do not build logic on a guarantee that does not exist.
13. As an LSP integrator, I want no event to be silently skipped even when transactions commit out of order,
    so that a quiet gap in my data never goes unnoticed.
14. As an LSP integrator, I want delivery to be at-least-once with a stable event identifier, so that I can
    dedupe on restart and know duplicates are an expected condition rather than a platform bug.
15. As an LSP integrator, I want every event to carry the loan it belongs to, so that I can route it to the
    right record without a second lookup.
16. As an LSP integrator, I want every event to carry when the change actually happened, so that my own
    records reflect business time and not my polling time.
17. As an LSP integrator, I want a schema version on every event, so that I can branch safely when the
    payload contract changes.
18. As an LSP integrator, I want to see only my own loans on my feed, so that a platform bug can never expose
    another LSP's borrowers to me.

### Falling behind and recovering

19. As an LSP integrator, I want a cursor older than the retention window to fail loudly, so that I discover
    I have fallen too far behind instead of silently receiving a truncated feed.
20. As an LSP integrator, I want the expiry response to name the resync path, so that I know exactly what to
    call to get back to a good state without reading documentation mid-incident.
21. As an LSP integrator, I want to resync by listing my loan applications through the endpoint that already
    exists, so that I do not have to integrate a second bulk-snapshot API.
22. As an LSP integrator, I want to know how long the retention window is, so that I can set my own alerting
    to fire before my consumer falls out of it.

### The lifecycle events themselves

23. As an LSP integrator, I want an event when a loan application is created, so that I can track it from the
    moment it enters the platform.
24. As an LSP integrator, I want an event when a loan application changes status, so that I can follow it
    through the approval path without polling each application.
25. As an LSP integrator, I want an event when a loan application is invalidated, so that I stop chasing an
    application that will never proceed.
26. As an LSP integrator, I want an event when required documents are uploaded, so that I know the checklist
    is progressing without asking.
27. As an LSP integrator, I want an event when a disbursement is requested, so that I can tell my borrower
    money is on its way.
28. As an LSP integrator, I want an event when a disbursement completes, so that I can confirm the borrower
    was actually credited.
29. As an LSP integrator, I want an event when a disbursement fails, so that I can act on it rather than
    waiting on a success that will never arrive.
30. As an LSP integrator, I want events for the intermediate disbursement states, so that a disbursement
    sitting in reconciliation is visible to me rather than looking indistinguishable from a stall.
31. As an LSP integrator, I want an event when a repayment is recorded, so that my ledger stays in step with
    the platform's.
32. As an LSP integrator, I want an event when a loan is fully repaid, so that I can close the loan on my
    side.
33. As an LSP integrator, I want an event when a foreclosure quote is requested and when foreclosure
    completes, so that I can follow early closure end to end.
34. As an LSP integrator, I want an event when borrower bank details change, so that I know which account a
    disbursement will actually land in.
35. As an LSP integrator, I want an event when a loan's delinquency state changes, so that I can begin
    collections at the right moment instead of recomputing days-past-due myself.

### Operating the platform

36. As a platform operator, I want the loan event log written unconditionally in the same transaction as the
    state change, so that the log records what happened rather than what configuration allowed.
37. As a platform operator, I want an alert on the age of the oldest open transaction, so that I learn a
    stalled transaction is freezing every tenant's feed before partners start calling.
38. As a platform operator, I want the loan event log partitioned by month with old partitions dropped, so
    that retention is enforced by a cheap partition drop rather than a large delete.
39. As a platform operator, I want partitions created ahead of time, so that writes never fail because next
    month's partition does not exist.
40. As a platform operator, I want per-LSP rate limiting on the feed, so that one partner polling aggressively
    cannot degrade the endpoint for everyone else.
41. As a platform operator, I want the feed to serve only committed transactions, so that a consumer can
    never observe an event whose transaction later rolls back.
42. As a platform operator, I want internal history to stay exactly as it is, so that the record of who
    changed what remains complete and separate from a partner delivery mechanism with a retention window.
43. As a platform operator, I want the webhook tables, workers, admin endpoints and alert rules removed
    entirely, so that no one has to reason about two lifecycle-update paths.
44. As a platform operator, I want the loan event log covered by the same row-level security approach the
    outbox already had, so that tenant isolation does not regress with the change.

### Internal correctness

45. As a platform engineer, I want loan account status changes to become durable recorded facts, so that a
    state the loan passed through is not lost to an overwritten column.
46. As a platform engineer, I want delinquency transitions to become durable recorded facts, for the same
    reason.
47. As a platform engineer, I want a single place where lifecycle events are appended, so that adding a new
    event type does not mean hunting through ten services for the right pattern.
48. As a platform engineer, I want the feed's correctness under concurrent commits proven by a test, so that
    the central claim of ADR 0007 is not merely asserted in prose.

## Implementation Decisions

### The loan event log

- A new `loan_event` table is the append-only loan event log. It is written unconditionally in the same
  transaction as the state change it describes. There is no subscription table, no enable flag, and no
  write-time filter. Who may read a given event is resolved at read time, from the owning LSP.
- Each row carries: a stable event identifier, the owning LSP, the event type, the aggregate type and
  identifier, the loan application it belongs to, the event payload, the business-event time, a correlation
  identifier, and the two ordering columns below.
- Ordering columns are the committing transaction's `xid8` and a monotonic position. Together they form the
  composite ordering key `(transaction_id, position)`.
- The table is partitioned by month on the business-event time. Retention is 30 days, enforced by dropping
  whole partitions. Partitions are created ahead of need so a write never lands with no partition present.
  There is no existing partitioned table in this schema, so this is the first — no prior art to follow.
- Row-level security on the owning LSP, plus the tenant application role grants, mirroring exactly what the
  outbox table already had. Tenant scope continues to come from the principal per ADR 0005.

### Cursor semantics

- The feed query serves only transactions that have definitively committed, by filtering on the transaction
  id being below the current snapshot's `xmin`. Postgres assigns sequence values and timestamps *before*
  commit, so a cursor over a bare sequence would permanently skip events from transactions that commit late.
  This filter is the reason the composite cursor exists and is not optional.
- Pagination is keyset pagination on the composite `(transaction_id, position)`.
- The cursor exposed to partners is an **opaque token** encoding that composite. Dense, gap-free sequence
  numbers were considered and rejected in ADR 0007: they forbid rollbacks consuming numbers, forbid
  per-tenant assignment, and hand partners a verification tool that alerts on legitimate gaps.
- Cursors are always over the **unfiltered** stream. The event-type filter narrows the response only, so an
  LSP that widens its filter can rewind and receive everything.
- A cursor older than the retention window returns `410` with a `CURSOR_EXPIRED` code naming the resync path.
  It never returns a silently truncated `200`.

### The partner API contract

- One endpoint: `GET /api/v1/lsp/loan-events`, taking `cursor`, `limit` and `eventTypes`, returning the
  events plus `nextCursor` and `hasMore`. It joins the existing LSP surface alongside loan applications,
  loans, borrowers and products, and inherits that surface's existing filters — IP allowlist, payload size,
  and tenant isolation.
- The response shape follows the existing cursor-paged result already used by the audit explorer, extended
  with `hasMore`.
- Ordering is guaranteed **per loan**, not globally. Concurrent transactions touching the same loan serialise
  on row locks, so per-loan order is real. Across unrelated loans the feed is a stable replay order, not a
  causal one. This is stated in the partner contract as the guarantee, because it is what the mechanism can
  actually keep.
- Delivery is **at-least-once**. Duplicates are expected on consumer restart; LSPs dedupe on the event
  identifier. This is a contract obligation, not an edge case.
- Resync uses the existing loan applications listing endpoint. No bulk snapshot endpoint is built.
- Per-LSP rate limiting uses the existing rate-limit filter and key strategy; the feed is registered as a
  rule there rather than growing its own limiter.

### Event payloads

- One versioned schema for every LSP, carrying **full, unmasked** loan and borrower data. Per-LSP field
  configuration is deliberately not part of this design (ADR 0007, as clarified by spec 003).
- The envelope keeps the existing shape — schema version, event type, occurrence time, aggregate type and
  identifier, LSP identity, and the payload — so partner-facing payloads are recognisably continuous with
  what the outbox envelope produced.

### Event production

- The existing `enqueueIfSubscribed` entry point is replaced by a single append operation on the loan event
  log, with the subscription check removed. This is the one choke point through which all lifecycle events
  already flow — there are 13 call sites across 10 services covering onboarding, status writing,
  invalidation, the document checklist, the disbursement command and intent workflow paths, disbursement
  outcome application, repayment, foreclosure, and borrower bank details. Every one moves to the new append.
- Two aggregates gain durable history they never had: loan account status and delinquency state are currently
  stored as current value only. The disbursement path and the days-past-due transitions become permanently
  recorded facts rather than overwritten columns, and both become event producers.
- The deprecated `LOAN_DISBURSEMENT_UPDATED` event type is **deleted**, not carried forward. It has no
  producer and exists solely so historical outbox rows still deserialize; with the outbox dropped and no
  production data, that reason is gone.

### Removing the webhook path

- The webhook delivery machinery is deleted, not flagged off: the outbox and delivery-attempt entities and
  repositories, the dispatch worker and executor, the delivery client, the redrive audit path, the outbox
  admin endpoints, and the dispatch configuration and properties. Roughly 25 main source files and 9 test
  files.
- **One thing named "webhook" is not part of the delivery path and must survive.** The static loan payload
  builder is used by nine of the ten producers to construct payload bodies; it is delivery-agnostic and is
  renamed to match the loan event log rather than deleted. Deleting it would break every producer.
- **The internal ops webhook-events view goes with the delivery path.** The per-loan-application projection
  record surfaces delivery data — target URL, attempt count, last attempt time, last response code, last
  error — over an internal ops endpoint, fed by the outbox listing. None of that survives a design where the
  platform does not push, so the projection, its response type, the ops endpoint, and the read-service
  methods behind it are removed with the rest. Its name is accurate and is left alone until it is deleted.
- The LSP entity loses its webhook enablement flag, endpoint URL, signing secret, and subscribed event types;
  the LSP admin surface loses the corresponding configuration and its webhook audit path.
- Schema removal drops the four webhook tables. Ten migrations introduced or amended them, and five further
  migrations touch them incidentally — optimistic locking columns, the row-level security grants and policy,
  the seeded dead-letter alert rule, a JSON-object check constraint, and a timestamp column conversion. The
  removal migration must account for all of these; the incidental five are not reverted, only their
  webhook-specific statements superseded.
- The seeded `WEBHOOK_DEAD_LETTER` alert rule is removed and replaced by the oldest-open-transaction-age
  alert, which ADR 0007 calls a launch blocker rather than a follow-up.
- Because the project is pre-production greenfield with no production data and no partner-compatibility
  constraint, the cutover is a **hard cutover**. No dual-write window, no expand–contract sequence, and
  destructive migrations are acceptable.

## Testing Decisions

A good test here asserts what a partner or operator can actually observe — the contents and order of the
feed, the status code on an expired cursor, the isolation between two LSPs. It does not assert that a
particular internal method was called, or reach into the log's storage layout to check a column. Two seams,
confirmed with the developer:

### Seam A — the partner HTTP surface

The primary seam. Tests drive a real lifecycle change through the existing command path and then assert what
the LSP sees on `GET /api/v1/lsp/loan-events`. Every Spring test context is already bound to a real
Testcontainers Postgres with Flyway enabled by `PostgresTestContextCustomizerFactory`, so this seam exercises
real `xid8`, real partitions and real row-level security rather than mocks.

Covered at this seam: event production from all producer call sites, event-type filtering, cursor advance,
`nextCursor` and `hasMore`, rewind after widening a filter, `410 CURSOR_EXPIRED`, per-LSP isolation,
duplicate tolerance, and the full-unmasked payload contract.

Prior art: `LspLoanApplicationApiControllerTest`, `LspStatusKillChainIntegrationTest`,
`Issue64LspSurfaceIpAllowlistIntegrationTest`.

### Seam B — Postgres, two connections with controlled commit order

The one guarantee Seam A structurally cannot reach: a single-threaded MockMvc test cannot hold two
overlapping transactions and commit them out of order. The test opens two transactions, commits the second
before the first, and asserts that a consumer paging with a cursor still receives the late-committing
transaction's events. This is the central correctness claim of the design and must be proven, not asserted.

Also covered here: monthly partition creation ahead of need, and the 30-day partition drop.

Prior art: `PostgresDataJpaTestSupport`, `SharedPostgresTestContainer`,
`WebhookEventOutboxRepositoryPostgresTest` (the pattern survives the table it tested).

### Existing tests

Tests that assert event production by Mockito-verifying `enqueueIfSubscribed` — in the disbursement outcome
applier test and the repayment concurrency integration test — are **replaced by feed-level assertions** at
Seam A rather than repointed at the new append operation. The nine webhook-specific test files are deleted
with the code they cover.

## Out of Scope

- **Payload minimisation.** Full, unmasked payloads are explicitly accepted for the pre-production contract.
  RBI (Digital Lending) Directions 2025 clause 13(i) makes minimisation a real obligation before production,
  and it will be revisited then as one versioned schema change for every LSP. Pruning is not retroactive for
  data already distributed.
- **Per-LSP payload configuration.** Deliberately not built. One schema for every partner.
- **A per-LSP consumer lag endpoint and stalled-consumer alerting.** Under webhooks a broken partner surfaced
  as dead letters; under polling a stalled consumer is indistinguishable from a quiet day. Accepted
  deliberately — the published guarantee is availability of events, never consumption. Both are small
  additions if the position changes.
- **A bulk snapshot endpoint.** Resync goes through the existing loan applications listing.
- **Push delivery in any form**, including a payload-free "you have events" nudge. Rejected in ADR 0007 on
  contract grounds: partners would treat the nudge as the delivery channel and stop polling.
- **Long polling and streaming transports.** At roughly 20 tenants polling every 10 seconds the aggregate
  load is about 2 requests/second; there is no cost problem to solve.
- **Logical replication or CDC.** Rejected — heavier infrastructure and a stuck replication slot can take
  down the primary.
- **LSP-held disbursal and collection accounts.** Flagged in ADR 0007 as a live compliance question under
  clause 9(ii)/(iii), but unrelated to this change.
- **Migration squashing.** Even though the project is greenfield, collapsing the existing migration history is
  a separate decision from removing the webhook path.

## Further Notes

- **Build order matters.** The loan event log and its producers must land before the webhook deletion. The
  reverse order leaves a window with no partner lifecycle channel at all. The tickets derived from this spec
  carry that as a blocking edge.
- **The new failure mode is worth stating plainly.** Any long-running transaction anywhere in the database —
  a `pg_dump`, a leaked connection, a stuck analytics query — becomes a ceiling on feed freshness for *every*
  tenant, because the feed only serves transactions below the snapshot `xmin`. The oldest-transaction-age
  alert is what makes this operable, which is why ADR 0007 calls it a launch blocker.
- **Security posture changes shape.** A retained, replayable feed is a better exfiltration target than a
  webhook stream: a leaked API key drains 30 days of history rather than intercepting future traffic. Per-LSP
  rate limiting and the short retention window are both part of the mitigation.
- **Volume is uncertain but does not change the design.** A €100M book of ₹5,000–50,000 tickets implies
  roughly 350K active loans and closer to ~1K new loans/day than the stated 100K/day peak. Only partition
  sizing depends on which is true.
- **Triggers to re-open** are recorded in ADR 0007 and are not restated here.
