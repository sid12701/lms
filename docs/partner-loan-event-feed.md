# Partner loan event feed (ADR 0007 / spec 004)

**Endpoint:** `GET /api/v1/lsp/loan-events`
**Auth:** LSP API client credentials (`POST /api/v1/auth/token`), on the same LSP surface as loan applications,
loans, borrowers and products — the same IP allowlist and tenant isolation apply.

The platform does not push lifecycle changes. You read your own loan events from this endpoint on a schedule
you choose, hand back the cursor you were last given, and read `hasMore` to decide whether to poll again
immediately or wait for your normal interval.

## Request

| Parameter | Required | Meaning |
|---|---|---|
| `cursor` | No | The `nextCursor` from your last response. Omit it to start from the beginning of the retained window. |
| `limit` | No | Events per response. Default `100`, hard maximum `500`. A value above the maximum is clamped down to `500`; a value below `1` falls back to the `100` default rather than clamping up to `1`, so `limit=0` returns a full page, not an empty one. The response's `limit` field tells you what was actually applied. |
| `eventTypes` | No | Narrows the response to the event types you name. Omit it to receive all of them. Repeat the parameter (`eventTypes=LOAN_CREATED&eventTypes=DOCUMENTS_UPLOADED`) or comma-separate one value (`eventTypes=LOAN_CREATED,DOCUMENTS_UPLOADED`). |

## Response

```json
{
  "items": [
    {
      "eventId": "0f2b…",
      "schemaVersion": 1,
      "eventType": "DISBURSEMENT_COMPLETED",
      "occurredAt": "2026-08-17T06:13:52.694949Z",
      "aggregateType": "LOAN_ACCOUNT",
      "aggregateId": "9c11…",
      "lspId": "3a7e…",
      "lspCode": "LSP-APEX",
      "loanApplicationId": "5d40…",
      "correlationId": "3772b2a2-…",
      "payload": { }
    }
  ],
  "nextCursor": "eyJ0cmFuc2FjdGlvbklkIjoi…",
  "limit": 100,
  "hasMore": false
}
```

`occurredAt` is the business-event time — when the change actually happened, not when you polled.

## What the platform guarantees

- **At-least-once delivery.** Duplicates are an expected condition, not a platform bug: a consumer that
  restarts from an older cursor, or retries a request whose response it never saw, receives events it has
  already processed. **Dedupe on `eventId`**, which is stable for the life of the event.
- **Ordering is per loan, not global.** Events for a single loan arrive in the order they happened, so you
  will never see a disbursement outcome before the approval that authorised it. Across unrelated loans the
  feed is a stable replay order, not a causal one — do not infer that loan B's event happened after loan A's
  because it came later in the feed.
- **No silent gaps.** The feed serves only transactions that have definitively committed. An event whose
  transaction was still in flight when you polled appears on a later poll; it is never skipped.
- **One schema for every partner.** Payloads are full and unmasked, and every event carries `schemaVersion`
  so you can branch safely when the contract changes.
- **Every event carries its loan** in `loanApplicationId`, so you can route it without a second lookup.

## What the platform does not guarantee

- **Consumption.** The published guarantee is that events are available, never that you consumed them. A
  stalled consumer is invisible to the platform, so alert on your own polling health.
- **Cursor durability beyond retention.** Events are retained for **at least 30 days** — see *Retention and
  cursor expiry* below for what "at least" means and what happens if you fall behind it. Persist your cursor
  durably across deploys and restarts, and alert well before 30 days of lag.

## The cursor

Treat `nextCursor` as an **opaque token**: store it and hand it back unchanged. Do not decode, parse, compare
or generate one. It encodes an internal composite ordering key that the platform is free to change. Dense,
gap-free sequence numbers were considered and deliberately rejected (ADR 0007) — gaps in any ordering you
might infer are legitimate and are not evidence of loss.

A drained feed echoes your cursor back, so you can store `nextCursor` unconditionally on every response
without special-casing the empty page.

A cursor that is not a token this platform issued is rejected with `422` / `INVALID_CURSOR`.

## Retention and cursor expiry

Events are retained for **at least 30 days**. It is a floor, not an exact age: the log is partitioned by
month, and a month is dropped only once everything in it is past 30 days old — so depending on where in the
month an event falls, it can survive anywhere from 30 to 60 days. Build against "at least 30 days"; do not
build against the extra.

If you poll again after falling behind retention, your cursor names an event the platform no longer has. That
is not treated as a quiet day — you get `410` with error code `CURSOR_EXPIRED`, naming the way back:

```json
{
  "status": 410,
  "error": "CURSOR_EXPIRED",
  "message": "Cursor points before the oldest event still retained. Resync by listing your loan applications at GET /api/v1/lsp/loan-applications, then resume the feed with no cursor to read from the beginning of the retained window.",
  "violations": [
    { "field": "cursor", "message": "points before the oldest event still retained" },
    { "field": "resyncPath", "message": "GET /api/v1/lsp/loan-applications" },
    { "field": "retainedFrom", "message": "2026-07-01T00:00:00Z" }
  ]
}
```

To resync: call the **existing** `GET /api/v1/lsp/loan-applications` to reconcile your own state against the
platform's, then resume the feed with **no cursor** — that reads from the beginning of the current retained
window. There is no separate bulk snapshot endpoint; the applications listing you already use is the resync
path.

`410 CURSOR_EXPIRED` is a different failure from `422 INVALID_CURSOR`, and worth telling apart in your
alerting: `INVALID_CURSOR` means the token itself is malformed — a bug in your integration, such as storing
a truncated or corrupted value. `CURSOR_EXPIRED` means the token was fine but your integration fell behind
retention — a lag problem, not a correctness problem. A cursor that is merely old but still inside the
retained window keeps working exactly as before; expiry is about falling out of retention, not about age on
its own.

## Filtering by event type

`eventTypes` narrows **the response only**. It does not change what a cursor means: cursors are always
positions in the unfiltered stream, so filtering costs you no history.

That has two consequences worth building on:

- **Widening your filter is safe.** When your integration starts handling a type it used to ignore, rewind to
  an older cursor — or drop the cursor entirely to restart from the beginning of the retained window — and
  read again with the wider filter. Every event you filtered out is still there, and you receive it now.
- **Widening alone does not rewind.** Events your narrow read paged past stay behind your cursor. Reading
  forward from the cursor you stored delivers new events only. If you want the history, rewind on purpose.

`hasMore` and `nextCursor` describe the filtered response: a narrow consumer is never told to poll again for
events it would only discard.

The event types the feed emits:

| Event type | Emitted when |
|---|---|
| `LOAN_CREATED` | An application is created. |
| `LOAN_STATUS_CHANGED` | An application moves between statuses, including invalidation. |
| `DOCUMENTS_UPLOADED` | The last required intake document completes the checklist. |
| `DISBURSEMENT_REQUESTED` | A disbursement is initiated with the bank. |
| `DISBURSEMENT_PENDING_RECONCILIATION` | A disbursement is parked awaiting reconciliation — initiated, and not yet resolved either way. |
| `DISBURSEMENT_COMPLETED` | The bank confirms the borrower was credited. |
| `DISBURSEMENT_FAILED` | A disbursement attempt failed. **Not always terminal** — see below. |
| `LOAN_REPAYMENT_RECORDED` | A payment is posted against a loan. |
| `LOAN_FULLY_REPAID` | The final installment clears. |
| `FORECLOSURE_QUOTE_REQUESTED` | A foreclosure quote is requested. |
| `LOAN_FORECLOSURE_COMPLETED` | A foreclosure settles the loan. |
| `BORROWER_BANK_DETAILS_UPDATED` | A borrower's bank details change. |
| `LOAN_DELINQUENCY_BUCKET_CHANGED` | A loan's days-past-due bucket changes. **Fires in both directions** — see below. |

### `DISBURSEMENT_FAILED` does not mean the loan is over

A failed attempt is not the same as a failed loan, and the two are not distinguishable from the event
type alone. Read `payload.applicationStatus` to tell them apart:

| `applicationStatus` | `payload.declineKind` | What it means |
|---|---|---|
| `REJECTED` | `BUSINESS` | The bank declined on the merits. Terminal — the loan will not fund. |
| `DISBURSEMENT_RETRY` | `TECHNICAL` | The rail failed, not the loan. A further attempt is expected, and you may later receive `DISBURSEMENT_REQUESTED` and `DISBURSEMENT_COMPLETED` for this same loan. |

Closing a loan on your side when you see `DISBURSEMENT_FAILED` will therefore write off loans that go
on to fund. Treat `REJECTED` as the terminal signal, not the event type.

The same applies to `DISBURSEMENT_PENDING_RECONCILIATION`: the attempt is parked, not abandoned, and
the loan account stays open while it is resolved.

### `LOAN_DELINQUENCY_BUCKET_CHANGED` fires in both directions

Every event carries `payload.fromBucket` and `payload.toBucket` — one of `CURRENT`, `DPD_1_30`,
`DPD_31_60`, `DPD_61_90`, `DPD_90_PLUS` — plus `maxDaysPastDue`, `previousMaxDaysPastDue`, and
`overdueAmount`, so you can act without recomputing the bucket from repayment history yourself.

The event is emitted only when the bucket itself changes, not on every days-past-due tick a scheduled
re-evaluation reconfirms, so a delinquent loan does not produce one event per day. It fires the same way
whether the loan is worsening — moving further into delinquency — or curing — catching up and moving
back toward `CURRENT`. `toBucket: CURRENT` is the cure signal: it means the loan is no longer overdue,
and is the signal a collections workflow should stop escalating on. There is no separate boolean for
"is this loan delinquent" — `toBucket` already says so.

That table is the whole vocabulary. An event type outside it is rejected with `422` / `INVALID_EVENT_TYPE`,
naming the value it could not read and listing the ones it knows. It is never silently dropped — a filter you
mistyped fails loudly rather than returning an empty feed you would mistake for a quiet day. Names are
matched case-insensitively.

Sending `eventTypes` with no types in it (`eventTypes=`) is rejected the same way rather than read as "all
types" — a config template that rendered empty should fail, not quietly widen your stream. **Omit** the
parameter to receive everything.

## Rate limits

The endpoint is rate limited **per LSP**, on the same request-throttling mechanism the rest of the LSP
surface uses. Your budget is your own: another partner's polling, however aggressive, never consumes it
and never throttles you.

The default budget is **120 requests/minute**, comfortably above what a 10-second poll interval needs.
Exceeding it returns `429` with error code `RATE_LIMIT_EXCEEDED` and a `Retry-After` header naming how
long to wait, in seconds — the same convention the rest of the LSP surface already uses for rate
limiting, so there is nothing new to learn here. Honour `Retry-After` and resume with the same cursor:
a throttled request consumed no events, so nothing was lost by waiting.

## Consuming the feed

```
cursor = load_durable_cursor()          # null on first ever run
loop:
    response = GET /api/v1/lsp/loan-events?cursor={cursor}&limit=200
    for event in response.items:
        if already_processed(event.eventId): continue
        apply(event)
        mark_processed(event.eventId)
    cursor = response.nextCursor
    save_durable_cursor(cursor)         # after processing, not before
    if not response.hasMore: sleep(poll_interval)
```

Save the cursor **after** processing a page, not before: saving first turns a crash mid-page into lost
events, whereas saving after turns it into duplicates, which the `eventId` dedupe already handles.
