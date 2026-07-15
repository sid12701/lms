# API Consistency Backlog (deferred breaking changes)

Outcome of the 2026-07-02 API contract consistency audit. The non-breaking fixes
shipped immediately (see "Shipped" below). Everything in "Deferred" changes an
existing wire contract for Admin/Ops/LSP consumers and is parked here for a
versioned rollout (`/api/v2` or an alias-and-deprecate window) once an external
LSP partner integration timeline is known.

## Shipped (2026-07-02, non-breaking)

- Deleted the dead `ApiEnvelope` class; the documented contract is now what the
  wire actually does: bare resource bodies + `X-Correlation-Id` response header.
- Response DTO ids retyped `String` → `UUID` and timestamps `String` →
  `Instant` across the LSP surface, `LoanApplicationOpsApiTypes`, and
  `LspAdminController` (wire-identical; OpenAPI schema types now match the
  request side).
- `POST /api/v1/lsp/loan-applications` returns the same detail shape as
  `GET /{applicationId}` (strict superset; adds `updatedAt`, `invalid*`,
  `loanAccount`, `lastActivity`).
- Unknown-JSON-field policy: **strict on every LSP write endpoint**
  (`@JsonIgnoreProperties(ignoreUnknown = false)`), with a dedicated
  `Unknown field 'x' is not permitted.` 400 from the handler.
- PII masking: `bankAccountNumber` masked (`XXXXXXXX<last4>`, matching the
  aadhaar mask) in LSP loan-application responses, the borrower-admin detail
  (`bankAccountNumberMasked` previously returned the raw value), bank-mismatch
  ops-alert details, and the `BORROWER_BANK_DETAILS_UPDATED` webhook payload.
  The full value is served only by the dedicated bank-details endpoints and the
  payment rail. PAN stays raw on the LSP surface (KYC matching).
- Webhook envelope: additive `schemaVersion: 1` and `occurredAt` fields;
  `LOAN_DISBURSEMENT_UPDATED` marked `@Deprecated` (kept only so historical
  outbox rows deserialize).
- Webhook signing secret is write-only: admin reads return
  `signingSecret: null` + `secretSet`; a blank/omitted secret on PUT keeps the
  stored one. Frontend dialog updated accordingly.
- Pagination: `X-Offset`/`X-Limit` now always emitted by paginated list
  endpoints; `X-Total-Count` stays behind `paginationDetails=ON` (it costs an
  extra COUNT query).
- OpenAPI (`/v3/api-docs`) enabled, auth-required (was `permitAll` while
  disabled).

## Deferred — needs a versioned rollout

### 1. `ApiError` slimming (audit F2)
Current error body triplicates the code (`code`, `error`, `errorCode`,
`errorReason`, `errorSource` all carry the same value) and ships two parallel
detail arrays (`violations` and `errors`). Target shape:

```json
{ "timestamp", "status", "code", "message", "path", "correlationId",
  "violations": [{ "field", "message" }] }
```

Migration: mark the duplicate fields deprecated in docs now; remove in v2.
Consumers should be told to switch on `code` only.

### 2. Cross-role field-name unification (audit F6/F7)
Same concept, different names between Ops and LSP surfaces:

| Concept | Ops | LSP | Canonical (proposed) |
|---|---|---|---|
| loan amount | `requestedAmount` | `loanAmount` | `loanAmount` |
| tenure | `tenureMonths` | `loanTenure` | `tenureMonths` |
| external loan id | `externalLoanId` | `lspLoanId` | `externalLoanId` |
| product ref | `productId/Code/Name` | `loanProductId/Code/Name` | `productId/Code/Name` |
| borrower name | `borrowerFullName` | `fullName` | `fullName` |
| email / mobile / dob / pan | `borrowerEmail`/`borrowerMobile`/`borrowerDateOfBirth`/`borrowerPan` | `emailAddress`/`mobileNumber`/`dob`/`panNumber` | pick one set |

Also request/response asymmetries: payment `postedAt` (request) vs
`paymentDate` (response); LSP create `productId` (request) vs `loanProductId`
(response). Fix by aliasing on input + emitting canonical on output, then
removing old names in v2.

### 3. Webhook payload normalization (audit F13)
Inner `payload` is an untyped map and names status differently per event
(`status` / `loanAccountStatus` / `paymentStatus` / `applicationStatus`).
Introduce typed per-event payload records behind `schemaVersion: 2`, align id
field names with the REST models, and add `processingFeeAmount` /
`netDisbursedAmount` parity to the REST `loanAccount` summary (today they exist
only in the disbursement webhook).

### 4. Pagination body envelope (audit F3/F4)
Move pagination metadata into a body envelope
`{ items, totalCount, offset, limit }` for all collection endpoints in v2
(header-only metadata is invisible to most generated clients). Until then the
headers above are the contract.

### 5. Smaller parked items

- **Status filter lenience:** an unknown `status` query value on the list
  endpoints deliberately returns an empty page instead of 400
  (`LoanApplicationQueryService.resolveStatus`). Decide 400-vs-empty in v2 and
  apply to all enum filters.
- **LSP status tokens:** `PUT /lsps/{id}/status` accepts the `DISABLED` alias
  (→ `INACTIVE`); create does not. Frontend also models a `SUSPENDED` status the
  backend enum does not have. Converge on the enum names.
- **`LspDocumentChecklistResponse.uploadedAt`** is populated from the entity's
  `updatedAt`, not `uploadedAt` — value/name mismatch to resolve.
- **Remaining internal-admin DTO retyping:** `ApiClientAdminController`,
  `UserAdminController`, `ReportAdminController`, `AuditExplorerController`,
  `OpsAlertController`, `HomeDashboardController`, `LspOptionsController`,
  `WebhookOutboxAdminController` still use `String` ids/timestamps
  (wire-identical, internal-only; align opportunistically).
- **MIS CSV bank account column** is masked in preview/CSV export (2026-07-03);
  full bank details are available only via the audited LSP
  `GET /api/v1/lsp/borrowers/{borrowerId}/bank-details` endpoint.
- **Published event catalogue:** the FE keeps a hand-written dot-string ↔ enum
  map (`features/lsps/api.ts`). Publish a canonical external event-name scheme
  (dot notation) with the v2 webhook payloads so partners don't reinvent it.
- ~~Regenerate `frontend/src/lib/api/generated/schema.ts`~~ — **done 2026-07-03**
  (snapshot re-exported via `OpenApiContractExportTest`, `generate:api-types`
  re-run; `secretSet` + UUID/date-time formats now in the generated types).
- ~~Update `scripts/indep-e2e` for bank-account masking~~ — **done 2026-07-03**
  (EC-112 verdict text made dynamic; EC-113 replaced with a real known-plaintext
  scanner over LSP create/detail + borrower-360; standing EC-112 guard added to
  `reports_webhooks_pii.py`; FINDINGS.md B1 marked resolved). Scripts are
  syntax-checked but need a live backend run to confirm.
