# Bhavana LMS Platform Decision Handbook

## Purpose

This handbook is the explanatory companion to the evidence-heavy
`BHAVANA-ARCHITECTURE-BUSINESS-AUDIT-2026-07-23.md`.

> **Remediation update — 2026-07-24:** The H-01 concurrent one-open-loan defect
> described in this handbook has been fixed in the current worktree with a
> borrower-scoped database lock and a write-boundary recheck. The original text
> is retained so the audit reasoning and the reason for the fix remain visible.

The audit answers:

- What exists?
- Does the specification match the implementation?
- What is unsafe, incomplete, or production-blocking?

This handbook answers the questions needed to understand and defend the platform:

- What is the platform actually trying to be?
- How does a loan move through it?
- Why were the major technical and architectural choices made?
- Which business decisions forced or strongly influenced those choices?
- What does each choice buy us?
- What does each choice cost us?
- What credible alternatives exist?
- When is the current design the right answer?
- When should it be improved or replaced?
- How should the design be explained without defending known weaknesses?

The intended reader does not need to memorize every class. The goal is to build a
mental model strong enough to reason from first principles.

---



## 1. The platform in one page



### What Bhavana LMS is

Bhavana LMS is a partner-operated lending platform.

It is not primarily:

- a borrower-facing loan marketplace,
- a general-purpose workflow engine,
- a bank ledger,
- a data warehouse,
- or a collection of independent microservices.

It is a system that allows lending-service-provider partners, or LSPs, to submit
loan applications through APIs while Bhavana retains control over:

- borrower identity,
- credit eligibility,
- product economics,
- application and loan state,
- disbursement orchestration,
- repayment schedules,
- repayment posting,
- delinquency,
- foreclosure,
- partner visibility,
- audit evidence,
- operational intervention,
- and downstream notification.



### The central architectural idea

The platform keeps the authoritative operational state in one relational
database and changes that state through explicit domain services and state
transitions.

External side effects—webhooks, object storage, and disbursement-provider
calls—are placed behind boundaries and, where money or reliable delivery is
involved, driven from durable database state.

That gives the platform a simple governing rule:

> Commit the business decision and the evidence first; perform recoverable
> external work from that durable state.

The implementation follows this rule well for webhooks and for the default
disbursement-intent path. It does not yet implement the complete accounting,
bank, privacy, and operational controls required for live production.

### Why a modular monolith is defensible

A loan approval affects an application, a loan account, a repayment schedule,
audit history, and partner notifications. A repayment affects a payment record,
an installment, account balances, terminal status, audit evidence, and a
webhook. These operations benefit from one transaction boundary.

A modular monolith therefore reduces:

- distributed transaction problems,
- partial cross-service updates,
- message-ordering problems,
- operational overhead,
- deployment coordination,
- and premature API contracts between teams that are still changing the
product.

The relevant alternative is not “monolith bad, microservices good.” The real
question is:

> Does a capability have an independent availability, scaling, ownership, or
> regulatory boundary strong enough to justify losing simple database
> transactions?

For the current platform, the answer is generally no.

### What is strong enough to defend

- One Spring Boot deployment organized into domain-oriented services.
- API-only partner origination.
- A global borrower identity with partner relationships.
- Immutable product versions.
- Explicit application/account/installment states.
- Principal-derived tenant scope with PostgreSQL row-level security.
- Database-backed idempotency.
- A transactional webhook outbox.
- A durable disbursement intent before provider side effects.
- Schedule validation and post-disbursement freezing.
- Bounded retries, leased workers, and operational evidence.



### What should not be defended as complete

- Atomic enforcement of one open loan per borrower.
- Live ICICI disbursement.
- Mock/live provider isolation.
- Maker-checker or signed straight-through-processing limits.
- Approval-time beneficiary freezing.
- A receipt/allocation/suspense/reversal ledger.
- Three-way financial reconciliation.
- CKYC submission.
- Production-grade PII masking/encryption/retention.
- Malware quarantine and actual KYC verification.
- Production disaster-recovery and capacity evidence.

The correct defense is:

> The architectural foundations are sound, the current launch scope is limited,
> and the missing production controls are known and have a concrete hardening
> path.

---



## 2. How to think about a loan in this platform



### The business journey

```text
LSP and product configured
        ↓
Partner authenticates
        ↓
Application submitted through API
        ↓
Global borrower resolved
        ↓
Documents uploaded / checklist satisfied
        ↓
Rules evaluate eligibility
        ↓
Approved application
        ↓
Loan account + repayment schedule created
        ↓
Disbursement intent created
        ↓
Provider outcome applied
        ↓
Active loan servicing
        ↓
Repayment / delinquency / foreclosure
        ↓
Closed account with retained evidence
```

This journey is supported by four kinds of state.

### 2.1 Identity and configuration state

This answers:

- Who is the partner?
- Is the partner active?
- Which API clients may act?
- Which product may the partner sell?
- Which version of the product applies?
- Who is the borrower?

Principal entities:

- LSP
- API client and token version
- internal user and role
- loan product
- loan product version
- LSP-product mapping
- borrower
- borrower-LSP relationship



### 2.2 Workflow state

This answers:

- Where is the application?
- Has an account been created?
- Has payout been requested or completed?
- Is the loan active, failed, delinquent, foreclosed, or closed?

Principal entities:

- loan application
- application status transition
- loan account
- repayment schedule installment
- disbursement intent
- disbursement request evidence
- foreclosure quote



### 2.3 Financial operational state

This answers:

- What principal and fee economics were approved?
- What installments are due?
- What payment was posted?
- What balance remains?

Current entities:

- immutable product version snapshot
- loan account
- repayment schedule installments
- loan payment transaction

Important qualification: these are operational servicing records, not yet a
complete double-entry or receipt-allocation ledger.

### 2.4 Evidence and integration state

This answers:

- Who changed what?
- What was sent to a partner?
- Was delivery retried?
- What alert requires attention?
- Which report or document was generated or accessed?

Principal entities:

- application audit and transition records
- intake audit
- bank-detail update audit
- webhook outbox and delivery attempts
- redrive audit
- operations alerts
- report request and download audit
- document metadata
- idempotency records



### The mental distinction that prevents confusion

Do not collapse these concepts:


| Concept              | Meaning                                                      |
| -------------------- | ------------------------------------------------------------ |
| Application          | A request for credit moving through origination and approval |
| Loan account         | The servicing contract created after approval                |
| Product              | Current catalog identity and configuration                   |
| Product version      | Immutable economics used by a historical application/account |
| Repayment schedule   | Contractual installment projection                           |
| Payment transaction  | Current record of an accepted exact-installment posting      |
| Receipt/journal      | The future accounting evidence needed for real collections   |
| Audit event          | Evidence that a business/admin action occurred               |
| Webhook event        | A partner notification that must be delivered reliably       |
| Disbursement intent  | Durable authority/work item to attempt a payout              |
| Provider request log | Evidence of an actual provider interaction                   |


---

## 3. The governing business decisions

The platform becomes easier to understand once its ten foundational business
decisions are treated as constraints, not incidental code choices.

### D1 — Origination is API-only

**Business meaning:** LSPs originate loans. Bhavana operations administer and
service them but do not create borrower applications manually.

**Technical consequence:** The authoritative create path is the authenticated
LSP API. The internal frontend is not a hidden second origination channel.

**Why this is defensible:**

- Partner accountability is clear.
- Duplicate integrations are avoided.
- Data contracts can be validated consistently.
- Bhavana does not accidentally become an assisted-origination call center.

**Trade-off:** There is no internal fallback when a partner integration is
unavailable.

**When to reconsider:** Only if the business explicitly introduces direct or
assisted origination. That would require a new channel identity, consent model,
operating process, and audit contract—not merely another controller.

### D2 — Credit decisioning is automated

**Business meaning:** Passing applications are automatically approved; failing
applications are rejected. There is no normal human-underwriter queue.

**Technical consequence:** Eligibility is implemented as deterministic rules
inside `LoanAutoApprovalRuleEngine`, followed by state transitions.

**Why this is defensible:**

- Decisions are reproducible.
- Operations cost and turnaround time remain low.
- Partners receive predictable outcomes.
- The current rules are simple enough to remain readable code.

**Trade-off:** Edge cases need an exceptional admin override, and policy changes
require a deployment.

**When to reconsider:** When decision policies change independently of software
releases, require nontechnical ownership, incorporate models, or need formal
underwriter exceptions.

### D3 — One global borrower and one open loan

**Business meaning:** The same PAN represents the same borrower across LSPs, and
the borrower should not hold multiple open Bhavana loans.

**Technical consequence:** PAN is globally unique; LSP access is modeled as a
relationship; open accounts are checked across all partners.

**Why this is defensible:**

- Credit exposure is calculated across the lender, not per distributor.
- A borrower cannot evade the rule by switching LSP.
- Identity corrections apply to one canonical customer.

**Trade-off:** This creates cross-tenant privacy, locking, and governance
requirements.

**Current defect:** The semantic rule exists, but concurrent approvals are not
serialized on the borrower. The identity choice is sound; its atomic
enforcement is incomplete.

### D4 — LSP deactivation is a kill chain

**Business meaning:** Disabling a partner must stop it from continuing to
originate or act through previously issued credentials.

**Technical consequence:** LSP status, API clients, token versions, mappings,
authentication, and business gates work together.

**Why this is defensible:** A status flag that does not revoke effective access
is not a control. The kill chain makes deactivation operationally meaningful.

**Trade-off:** Status changes have a wide blast radius and deserve strong audit
and, eventually, approval controls.

### D5 — Processing fee is withheld from payout

**Business meaning:** The borrower owes the gross sanctioned principal while
receiving net cash after the processing fee.

**Technical consequence:** The account and schedule are based on gross
principal; disbursement uses a lower net amount.

**Why this is defensible:** It matches the selected commercial model and keeps
the fee economically financed.

**Trade-off:** Disclosure, APR, GST/tax, and accounting must be unambiguous.

### D6 — Repayments are full-installment only

**Business meaning:** The current product accepts one complete installment at a
time.

**Technical consequence:** Repayment rejects any amount other than the target
installment outstanding.

**Why this was logical:** It sharply reduces allocation ambiguity and allowed a
safe servicing slice to be built quickly.

**Trade-off:** Real collection scenarios—partial, bunched, advance, bounce,
reversal, and suspense—cannot be represented.

**Correct position:** Defensible for synthetic UAT; not defensible as the final
collections accounting model.

### D7 — The schedule freezes after disbursement

**Business meaning:** Once money is paid, contractual dues cannot be silently
rewritten.

**Technical consequence:** Schedule replacement is allowed only before
disbursement and must pass validation.

**Why this is defensible:** It protects borrower terms, auditability, interest
calculations, and collections consistency.

**Trade-off:** Corrections after disbursement must be modeled as explicit
adjustments or restructures rather than edits.

### D8 — Tenant isolation is mandatory

**Business meaning:** An LSP may see only the loans and borrowers it is entitled
to see, while Bhavana administrators can operate across the portfolio.

**Technical consequence:** Tenant identity comes from the authenticated
principal, an explicit tenant context selects access mode, and PostgreSQL RLS
provides a database backstop.

**Why this is defensible:** Application filters alone are easy to omit. RLS
reduces the blast radius of a repository/query mistake.

**Trade-off:** Admin-scope transactions, tests, migrations, and debugging are
more complex and PostgreSQL-specific.

### D9 — Actions must be auditable

**Business meaning:** Material actions need actor, time, reason, prior/resulting
state, and correlation evidence.

**Technical consequence:** Important workflows write dedicated audit/transition
records and webhook evidence.

**Why this is defensible:** Lending disputes, operations, compliance reviews,
and incident reconstruction require durable evidence.

**Current qualification:** The intention is broader than the implementation.
Several reads, alert actions, and sensitive exports need stronger audit.

### D10 — There is no borrower portal

**Business meaning:** Borrower experience remains with the LSP or another
channel.

**Technical consequence:** Authentication, authorization, and UI are designed
for internal users and partners, not consumers.

**Why this is defensible:** It avoids prematurely building consumer identity,
consent, support, notification, and accessibility capabilities outside the
current operating model.

---



## 4. Cross-cutting technical patterns

These patterns recur across features. Understanding them is more valuable than
memorizing individual controllers.

### 4.1 Explicit state machines

Applications and accounts do not accept arbitrary status strings. Services
validate permitted transitions and append transition/audit evidence.

**Why chosen:**

- Lending state has business meaning.
- Invalid jumps can create money and reporting inconsistencies.
- Explicit transitions are easier to test and explain.

**Alternative:** A general workflow engine.

**Why not yet:** A workflow engine would move policy out of readable domain code,
add operational infrastructure, and still require financial invariants in the
application/database.

**Better implementation:** Keep the explicit state machine. Add a generated
transition catalog, stronger invariants, and dedicated commands for exceptional
business events rather than a generic “set status.”

### 4.2 Relational transactions for business invariants

Application, account, schedule, audit, and outbox writes generally share a
database transaction.

**Why chosen:** These records must agree immediately. The database provides the
simplest atomic boundary.

**Alternative:** Eventual consistency across services.

**When alternative is better:** Only when a capability needs independent
availability or scaling and the business explicitly accepts intermediate
inconsistency.

### 4.3 Immutable snapshots

Product versions and post-disbursement schedules preserve historical terms.

**Why chosen:** A catalog change must not rewrite yesterday's loan.

**Alternative:** Store only a product reference and read current configuration.

**Why alternative is unsafe:** Historical balances, pricing, reports, and
disputes would change when the catalog changes.

### 4.4 Database-backed idempotency

Retried partner/admin commands can resolve to one durable result instead of
performing duplicate work.

**Why chosen:** HTTP retries, network timeouts, and client uncertainty are
normal, not exceptional.

**Alternative:** In-memory deduplication or “clients should not retry.”

**Why alternative is unsafe:** It fails across restarts, multiple nodes, and
ambiguous responses.

**Better implementation:** Require idempotency, rather than merely allowing it,
for every money-relevant or externally retried mutation.

### 4.5 Transactional outbox

Partner events are persisted with the state change and delivered later.

**Why chosen:** A database commit followed by a synchronous webhook can lose the
notification if the process crashes between the two. Calling the partner inside
the transaction can hold locks and still cannot atomically commit both systems.

**Alternative:** Publish directly to a broker.

**Why direct publish is insufficient:** Without a database outbox or CDC
transaction bridge, the application can commit without publishing or publish
without committing.

**Current verdict:** One of the strongest design choices. Retain it.

### 4.6 Durable intent for money movement

A disbursement intent is committed before a provider is called. The provider
call occurs outside the initial transaction, and the outcome is persisted
afterwards.

**Why chosen:** A timeout after sending money does not reveal whether the bank
paid. Retrying blindly can pay twice.

**The three phases:**

1. **Tx-A:** validate and persist intent/reference.
2. **Outside transaction:** call provider.
3. **Tx-B:** persist outcome; ambiguous results move to inquiry/reconciliation.

**Alternative:** Call the provider synchronously and then save.

**Why alternative is unsafe:** A crash or database rollback after a successful
bank call loses the local record of money movement.

**Better implementation:** Make the intent path mandatory and remove the legacy
inline branch.

### 4.7 Leased workers

Workers claim rows for a bounded period rather than marking work complete before
execution.

**Why chosen:** Multiple nodes can process work without permanent loss when one
node crashes.

**Alternative:** A single scheduled process with an in-memory lock.

**Why alternative is limited:** It does not scale across nodes and cannot recover
cleanly after process failure.

### 4.8 Defense-in-depth tenancy

Authorization decides who may call; tenant context decides which LSP scope
applies; RLS constrains what rows the database returns.

**Why chosen:** Security should not depend on every query author remembering a
predicate.

**Alternative:** Database per tenant.

**Why not chosen:** It makes global borrower identity and portfolio operations
far harder, creates migration overhead per tenant, and is disproportionate at
current scale.

---



## 5. Major feature implementation and decision guide



## 5.1 Internal authentication, users, roles, and sessions



### What the feature does

It authenticates internal operators, issues and refreshes sessions, enforces
roles, revokes access, and protects administration/operations APIs.

### How it is implemented

1. Login validates internal-user credentials.
2. JWT/access and refresh-session state are issued.
3. Security configuration maps API paths to roles.
4. Refresh rotation and token-version/session state allow revocation.
5. User deactivation invalidates effective future access.
6. Non-local startup validation rejects default bootstrap/JWT secrets.



### Business decision behind it

Bhavana staff, not borrowers, operate the internal platform. A small number of
coarse roles was chosen over a large entitlement system.

### Why the architecture is logical

- Spring Security is the standard enforcement boundary.
- Session/token-version state makes revocation possible despite stateless JWTs.
- Coarse roles reduce policy ambiguity while the operating model is small.



### Credible alternatives


| Alternative                   | When it is better                                                         | Cost/trade-off                                |
| ----------------------------- | ------------------------------------------------------------------------- | --------------------------------------------- |
| Corporate OIDC/SSO with MFA   | Production staff access and centralized joiner/mover/leaver controls      | Identity-provider dependency and role mapping |
| Fine-grained permissions/ABAC | Duties separate across payout, PII, reconciliation, and security admins   | Policy complexity and harder testing          |
| Server-side opaque sessions   | Immediate centralized revocation is more important than stateless scaling | Session-store availability on every request   |




### Recommendation

Retain the present structure for the prototype. Before production, integrate
corporate identity/MFA and split sensitive capabilities—payout approval, PII
reveal, report export, reconciliation, user administration—into explicit
permissions.

### How to defend it

> We used standard framework security and revocable session state rather than
> inventing authentication. Coarse roles match the current operating team, with
> a clear path to capability-level segregation as duties mature.

---



## 5.2 Partner authentication and the LSP kill chain



### What the feature does

It allows machine clients belonging to an LSP to authenticate, receive scoped
tokens, call partner APIs, and be revoked when the client or LSP is disabled.

### How it is implemented

- API client secrets are stored as hashes.
- Tokens include LSP identity and version/revocation context.
- Authentication checks client and LSP status.
- LSP deactivation revokes or invalidates client/token utility.
- Product mappings and downstream business gates also honor LSP status.



### Why this is more than a login feature

The important design is the kill chain. A partner status change must disable:

- new authentication,
- already issued authority,
- new product use,
- and new business actions.

A status column alone would give false assurance.

### Alternatives


| Alternative                       | Assessment                                                                                    |
| --------------------------------- | --------------------------------------------------------------------------------------------- |
| API gateway-managed OAuth clients | Useful when a mature gateway owns client lifecycle, quotas, and token introspection           |
| Mutual TLS plus OAuth             | Stronger partner/device identity for bank-grade integrations, but more certificate operations |
| Static API keys                   | Simpler, but weak rotation/revocation/scoping; not preferred                                  |




### Recommendation

Retain the current client/token design. Add secrets-manager issuance, expiry,
rotation, mTLS if required by partner risk, and maker-checker for LSP
activation/deactivation.

### How to defend it

> Partner deactivation is implemented as an effective access kill chain, not a
> cosmetic status. Credentials, tokens, mappings, and runtime business checks
> agree on whether the partner is active.

---



## 5.3 Multi-tenancy and partner-visible data



### What the feature does

It prevents one LSP from seeing another LSP's loans while allowing Bhavana
administrators to operate across the lender portfolio.

### How it is implemented

1. The authenticated principal supplies the authoritative LSP identity.
2. A filter establishes tenant context.
3. The routing datasource fails closed if context is missing.
4. PostgreSQL RLS limits tenant-visible rows.
5. Admin-scoped executors explicitly switch to portfolio-wide access for
  approved cross-LSP use cases.
6. Borrower visibility is granted through LSP relationship/access data.



### Business rationale

The LSP is a distributor/servicer with a legitimate need to see its own
relationship, not the lender's entire customer portfolio.

### Alternatives


| Alternative                 | Benefit                             | Why it is not currently preferred                           |
| --------------------------- | ----------------------------------- | ----------------------------------------------------------- |
| Application predicates only | Database portability and simplicity | One missed predicate can expose another tenant              |
| Schema per LSP              | Stronger namespace separation       | Migration/operations overhead; hard global borrower queries |
| Database per LSP            | Maximum infrastructure isolation    | High cost; fragmented lender view; difficult cross-LSP D3   |
| Separate read service       | Centralized policy                  | Additional service consistency and availability boundary    |




### Recommendation

Retain shared-schema RLS. Strengthen PostgreSQL integration tests, admin-scope
auditing, and complete the borrower-relationship-table cutover.

### How to defend it

> Tenant scope is derived from identity, not trusted from request input, and the
> database independently enforces row visibility. Shared schema is deliberate
> because the lender needs a global borrower and portfolio view.

---



## 5.4 LSP onboarding and product mapping



### What the feature does

It configures which partners are active and which loan products each partner may
originate.

### Implementation

- LSP administration controls lifecycle status.
- Product mappings form an explicit allowlist.
- Intake and credit decisioning recheck LSP, product, and mapping status.
- Disabling a mapping stops future use without mutating historical loans.



### Governing business choice

Products are lender-controlled. Partners receive permission to distribute a
product; they do not implicitly gain access to every catalog item.

### Alternatives

- Embed partner IDs directly inside product records.
- Copy a separate product per LSP.
- Use a policy engine for product eligibility.

The join/mapping entity is better because it represents the many-to-many
business relationship directly and avoids duplicating product economics.

### Better future version

Add effective dates, contractual limits, approval history, and per-LSP pricing
only if the business genuinely differentiates economics. Do not add these
preemptively.

---



## 5.5 Product catalog and immutable product versions



### What the feature does

It defines principal limits, tenure, interest, fees, and other product
economics, while preserving the exact version used by each application and
account.

### Implementation

1. The product is the stable catalog identity.
2. A configuration change creates a new immutable product-version row.
3. Intake resolves the latest valid version.
4. The application and account reference that version.
5. Historical loans continue to use their original economics.



### Why this design matters

If a product's interest rate changes from 18% to 20%, an existing loan must not
silently become a 20% loan. Versioning is a contractual and audit control, not
merely a software pattern.

### Alternatives


| Alternative                                         | Result                                                                        |
| --------------------------------------------------- | ----------------------------------------------------------------------------- |
| Update product in place                             | Simple, but corrupts or ambiguously reconstructs historical terms             |
| Copy all economics onto application/account columns | Strong snapshot but duplicates schema and complicates evolution               |
| Event-source every product change                   | Complete history but disproportionate complexity                              |
| Effective-dated product versions                    | Stronger future scheduling; useful when products are approved ahead of launch |




### Recommendation

Retain immutable versions. Consider approval/effective dating and version hashes
when production product changes need maker-checker.

### How to defend it

> Product configuration is mutable for future business, but the terms of an
> existing loan are immutable. The stable product identity and immutable
> version separate those two needs.

---



## 5.6 API loan application intake



### What the feature does

It accepts an LSP's application, validates partner/product eligibility, resolves
the borrower, prevents obvious duplicates, seeds document requirements, records
evidence, and queues partner notification.

### End-to-end implementation

1. Partner authentication establishes LSP scope.
2. Request validation checks required fields and payload shape.
3. Durable idempotency protects retryable create requests.
4. The service verifies active LSP, product, version, and mapping.
5. Partner external-loan ID is checked for uniqueness within the LSP.
6. Amount, tenure, and interest are checked against configured economics.
7. PAN resolves or creates the global borrower.
8. Identity conflicts and existing open loans reject with an operations alert.
9. The application snapshots the product version.
10. Intake audit and document checklist are created.
11. A `LOAN_CREATED` webhook is added to the outbox.



### Business problem solved

The lender needs one controlled contract for multiple LSPs without allowing each
partner to implement lender policy differently.

### Why synchronous intake was reasonable

The validations are local and fast, and the caller benefits from an immediate,
deterministic acceptance or rejection. An asynchronous job would add states and
polling without solving a current bottleneck.

### Important weakness

The one-open-loan rule is checked but not atomically enforced during concurrent
approvals. The right improvement is a borrower-level lock/guard and recheck in
the approval transaction—not a microservice.

### Alternatives


| Alternative                        | Prefer when                                           | Impact                                                           |
| ---------------------------------- | ----------------------------------------------------- | ---------------------------------------------------------------- |
| Asynchronous `202 Accepted` intake | Heavy external verification or very high burst volume | Job state, polling/callbacks, more idempotency and UX complexity |
| Idempotent bulk API                | File/batch partners dominate                          | Row-level result model, quotas, partial failure handling         |
| Event ingestion                    | Partners already use a governed event backbone        | Schema registry, delivery semantics, replay governance           |
| Internal manual intake             | Business adds assisted origination                    | New actor/channel/consent and control model                      |




### How to defend it

> Intake is synchronous because the authoritative checks are local and the
> partner needs an immediate contractual result. It is idempotent, tenant-bound,
> versioned, audited, and emits downstream work transactionally.

---



## 5.7 Global borrower identity and relationships



### What the feature does

It represents one borrower across LSPs while separately recording which LSP may
see that borrower.

### Implementation

- PAN is normalized and globally unique.
- Mobile/Aadhaar conflicts trigger review rather than silent merging.
- Existing borrower data is merged under controlled rules.
- LSP visibility/relationship is granted during onboarding.
- Cross-LSP open-loan checks use admin scope.



### Business rationale

The lender owns aggregate exposure even when distribution is decentralized.
Tenant-local borrowers would allow the same person to appear unrelated across
partners.

### Alternatives


| Alternative                              | When it is better                                              | Cost                                                    |
| ---------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------- |
| Tenant-local borrower                    | Legally separate lenders with no shared exposure               | Duplicate identities and no lender-wide D3              |
| Dedicated master-party service           | Many products/systems require one enterprise customer identity | Distributed consistency, ownership, matching complexity |
| External KYC/identity provider as master | Provider offers authoritative persistent identity              | Vendor dependency and correction/availability concerns  |




### Better implementation

- Serialize approval by borrower.
- Distinguish immutable identity from mutable contact/profile data.
- Complete relationship-table cutover.
- Apply field-level consent/disclosure policy.
- Encrypt/tokenize high-risk identity values.



### How to defend it

> Global borrower identity is a credit-risk decision, not a tenancy mistake. We
> isolate partner visibility through relationships and RLS while retaining the
> lender's consolidated exposure view.

---



## 5.8 KYC document checklist, upload, and storage



### What the feature does

It defines required documents, accepts partner/internal uploads, stores object
metadata/content, and prevents approval/disbursement until required evidence is
present.

### Implementation

- Checklist rows are seeded from document requirements.
- Uploads validate size, type, file signature, filename, checksum, and metadata.
- Objects are stored in an S3-compatible R2 boundary.
- Downloads are authorized and audited.
- Completeness gates are called from approval/disbursement.



### Governing business choice

The current product treats required-document presence as the gating fact.

### Critical conceptual distinction

The implementation proves:

> A permitted file was uploaded for a required document slot.

It does not prove:

> The identity document is authentic, matches the borrower, passed sanctions or
> KYC checks, and has been approved by an authorized reviewer.



### Alternatives


| Alternative                          | Capability                                      | Cost                                       |
| ------------------------------------ | ----------------------------------------------- | ------------------------------------------ |
| Manual reviewer verdict              | Human verification and reasoned reject/reupload | Operations queue and SLA                   |
| KYC vendor integration               | Automated extraction/verification               | Vendor cost, false results, fallback       |
| Hybrid automated + manual exceptions | Scalable normal path with human review          | Most complete but most workflow complexity |
| Partner assertion only               | Minimal LMS scope                               | Weak lender control and evidence           |




### Recommendation

Keep checklist/presence as the base layer. Add quarantine/malware scanning and
explicit `PRESENT`, `SCANNED_CLEAN`, `VERIFIED`, `REJECTED`, `SUPERSEDED` states
if the LMS is expected to claim KYC completion.

### How to defend it

> The current document subsystem is a completeness and evidence-storage control,
> not yet a full KYC-verification engine. That boundary is intentional and must
> be described accurately.

---



## 5.9 Automated credit decisioning



### What the feature does

It re-evaluates eight live eligibility rules and automatically approves or
rejects an application.

### Rule groups

- Product is active.
- LSP is active.
- LSP-product mapping is active.
- Amount is in range.
- Tenure is in range.
- Required borrower fields exist.
- Required documents are complete.
- Borrower has no other open loan.



### Implementation approach

The engine is read-only and returns structured failures. Lifecycle services own
status mutation, account creation, audit, and webhook effects.

### Why this separation is good

- Pure evaluation is easier to test.
- The engine does not secretly mutate workflow state.
- Failure reasons can be explained.
- Transition logic remains centralized.



### Alternatives


| Alternative                     | Use when                                                | Trade-off                                         |
| ------------------------------- | ------------------------------------------------------- | ------------------------------------------------- |
| Database-configured rule tables | Nontechnical owners frequently change simple thresholds | Validation/versioning and expression complexity   |
| DMN/rules engine                | Large decision tables and formal business ownership     | New runtime/tooling and harder debugging          |
| External decision service       | Shared enterprise decisioning or ML                     | Network dependency, version correlation, fallback |
| Human underwriting queue        | Judgment-heavy or exception-rich products               | Cost, latency, inconsistency                      |




### Recommendation

Keep rules in code until rule complexity or ownership proves otherwise. Add
rule-set versioning to decision evidence and atomically enforce D3.

### How to defend it

> A dedicated rules engine would not improve eight deterministic checks. The
> current engine is explicit, testable, explainable, and separated from state
> mutation. We will externalize policy only when policy complexity or ownership
> requires it.

---



## 5.10 Application lifecycle, cancellation, and manual override



### What the feature does

It controls legal movements between application states, records transitions,
supports partner pre-disbursement invalidation, and gives privileged operations
an exceptional override path.

### Why explicit transitions are necessary

Status is not cosmetic. It determines whether the platform may:

- approve,
- create an account,
- disburse,
- retry,
- cancel,
- service,
- foreclose,
- or close.



### Hard cancellation versus soft invalidation

The platform retains the application and marks it invalid rather than deleting
it. This preserves:

- partner history,
- audit evidence,
- duplicate detection,
- and reporting context.

That is preferable to hard deletion in a regulated workflow.

### Current defect

`REJECTED` is described as terminal but can be moved to `INVALID` through the
invalidation classification. This is a taxonomy/transition mismatch.

### Alternatives

- Model cancellation as a separate terminal state.
- Preserve original status and attach a cancellation/invalidation reason record.
- Use a general workflow engine.

The cleanest improvement is likely an explicit terminal cancellation outcome or
an orthogonal invalidation marker, chosen by business reporting semantics.

### Manual override

An override is an operational safety valve. It should not become the normal
workflow.

Production improvements:

- mandatory reason codes,
- step-up authorization,
- maker-checker for money-relevant changes,
- idempotency,
- alerting,
- and periodic override review.

---



## 5.11 Loan account and repayment schedule



### What the feature does

Approval creates the serviceable loan contract and its installment schedule.

### Implementation

- One account is linked to one approved application.
- It retains borrower, LSP, product, and product-version references.
- Schedule generation is idempotent.
- Partner-provided schedule replacements must pass date/interest validation.
- Schedule mutation stops after disbursement.
- Optimistic versions and locks protect concurrent updates.



### Business rationale

The application is a request; the account is the approved obligation. Separating
them avoids treating rejected or pre-approval applications as loans.

### Platform-generated versus LSP-provided schedule

The platform supports controlled partner schedule input before payout because
partners may own origination calculations, while LMS validation protects the
lender contract.

### Alternatives


| Alternative                           | Strength                      | Weakness                                  |
| ------------------------------------- | ----------------------------- | ----------------------------------------- |
| LMS always generates                  | Maximum consistency           | May conflict with partner contract/source |
| LSP schedule accepted as-is           | Integration simplicity        | Unsafe economics/dates                    |
| External calculation engine           | Shared calculation governance | Dependency and version correlation        |
| Store only formula, calculate on read | Less storage                  | Historical and performance ambiguity      |




### Recommendation

Retain persisted schedules and validation. Introduce explicit adjustment or
restructure records for post-disbursement changes rather than allowing edits.

---



## 5.12 Borrower bank-detail updates



### What the feature does

It allows controlled updates to beneficiary bank data with validation, holder
matching, in-flight disbursement protection, and audit history.

### Why bank details are different from ordinary profile data

A spelling correction changes presentation. A bank-account change changes where
money goes. It therefore needs stronger controls than a normal borrower update.

### Current implementation logic

- Validate bank fields and holder name.
- Audit before/after context.
- Block updates when disbursement is already in flight.
- Read live bank details when creating the disbursement intent.



### Current weakness

Approval does not freeze the beneficiary. A change after approval but before
intent creation can alter the destination.

### Alternatives


| Alternative                                    | Assessment                                               |
| ---------------------------------------------- | -------------------------------------------------------- |
| Snapshot at approval and reject change         | Safest, but operationally rigid                          |
| Snapshot at approval and require reaffirmation | Best balance; recommended                                |
| Always use live borrower record                | Simple, but payout mandate can drift                     |
| Separate immutable beneficiary mandate entity  | Strongest long-term model for multiple mandates/accounts |




### Recommendation

Create an approval-time beneficiary snapshot or mandate, compare it at payout,
and require audited reapproval when it changes.

---



## 5.13 Disbursement orchestration



### What the feature does

It validates that a loan is ready for payout, creates durable payout work,
selects a payment mode, invokes a provider, applies outcomes, retries technical
failures, and escalates ambiguous outcomes.

### Current default implementation

1. Lock the application.
2. Validate application/account state.
3. Validate documents, schedule, partner/product, bank details, and attempt
  limits.
4. Calculate net disbursal from gross principal and processing fee.
5. Create one live disbursement intent with a deterministic reference.
6. Snapshot beneficiary details into the intent.
7. Commit.
8. A leased worker claims the intent.
9. Call the adapter outside the transaction.
10. Persist success, business failure, technical failure, pending, or unknown.
11. Apply terminal state and enqueue webhooks, or schedule status inquiry.



### Why this is the correct architecture

Money APIs have ambiguous failure. A timeout may mean:

- the request never reached the bank,
- the bank rejected it,
- the bank accepted it but the response was lost,
- or the transaction remains pending.

The safe response is not “retry the payment.” It is “persist the original
reference and inquire.”

### What the adapter boundary buys us

The domain workflow does not need to know ICICI encryption, endpoints, headers,
or error codes. A real provider adapter can translate provider-specific behavior
into normalized LMS outcomes.

### Alternatives


| Alternative                             | Money safety                              | Operational complexity | Verdict                                   |
| --------------------------------------- | ----------------------------------------- | ---------------------- | ----------------------------------------- |
| Synchronous provider call then DB write | Low under ambiguous failure               | Low initially          | Reject for live money                     |
| Durable DB intent + worker              | High                                      | Moderate               | Current/recommended                       |
| Broker command + consumer               | High only with durable source/idempotency | Higher                 | Useful later, but does not replace intent |
| Bank portal maker-checker/manual payout | Can be high                               | High human effort      | Viable temporary low-volume model         |
| Provider-owned idempotency only         | Depends on provider contract              | Moderate               | Insufficient as sole control              |




### What is missing

- Real ICICI HTTP/crypto/status implementation.
- Mutually exclusive mock/live configuration.
- Production startup guards.
- Maker-checker/STP limits.
- Approval-time beneficiary control.
- Complete bank reconciliation.
- Reversal/return handling.



### How to defend it

> We separated payout authorization from provider execution. A deterministic,
> durable intent exists before any external side effect, and ambiguous outcomes
> are resolved by status inquiry rather than blind retry. The provider-specific
> implementation is the missing layer, not a reason to redesign the core
> workflow.

---



## 5.14 Repayment posting and closure



### What the feature does

It accepts a payment against an installment, prevents duplicate posting, locks
the target data, updates balances, closes the account when fully paid, records
evidence, and emits partner notification.

### Current implementation strengths

- Durable idempotency.
- Short transaction boundaries.
- Pessimistic installment locking.
- Optimistic entity versions.
- Exact expected amount validation.
- Account closure and webhook state are coordinated.



### Why exact-EMI posting was chosen

Allocation becomes trivial: one payment settles one installment. This reduces
the risk of misallocating principal and interest while the product is being
validated.

### Why it is not the final financial model

Collections receive money before deciding how to allocate it. Real inputs can
be:

- partial,
- multiple installments,
- early/advance,
- overpaid,
- bounced,
- reversed,
- duplicated,
- or received without enough reference data.

The platform currently rejects or cannot represent those cases.

### Better architecture

Add layers:

1. **Receipt:** immutable evidence that money arrived.
2. **Allocation:** how the receipt affects dues/principal/interest/fees.
3. **Suspense:** unallocated or excess funds.
4. **Journal:** balanced accounting entries.
5. **Reversal/correction:** additive counter-entries, never destructive edits.
6. **Projection:** current installment/account state.



### Alternatives


| Alternative                                                    | Recommendation                                                                        |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| Let partner pre-allocate and trust it                          | Insufficient as lender's authoritative book                                           |
| Expand current payment table with nullable fields              | Quick but becomes an implicit, fragile ledger                                         |
| Introduce receipt/allocation/journal beside current projection | Recommended incremental approach                                                      |
| Replace servicing with full event sourcing                     | Unnecessary; journal plus relational projections is enough                            |
| Buy an external collections/ledger platform                    | Consider if build/control economics favor vendor and integration contracts are strong |




### How to defend it

> The current exact-installment path is intentionally narrow and concurrency
> safe. We should preserve it as a servicing projection while adding an
> immutable receipt and journal layer before becoming the collections system of
> record.

---



## 5.15 DPD bucketing and delinquency alerts



### What the feature does

It derives days past due from unpaid installments, assigns delinquency buckets,
records snapshots/transitions, and raises operational alerts.

### Why a derived read model is appropriate

DPD is a function of contractual due dates, payment state, and the evaluation
date. Storing snapshots supports:

- operational queues,
- trend/reporting,
- evidence of bucket movement,
- and efficient reads.



### Alternatives

- Compute DPD on every request.
- Maintain it only as a mutable account column.
- Calculate it entirely in a warehouse.

The current combination—authoritative installments plus operational
snapshots—is a sound choice. Compute-on-read alone is expensive and loses
history; warehouse-only calculation is too late for operations.

### What needs business decisions

- timezone and end-of-day cutoff,
- holidays/grace,
- moratorium,
- backdated payment,
- write-off,
- foreclosure interaction,
- regulatory and bureau semantics.



### Recommendation

Keep the architecture and version the policy used for each snapshot.

---



## 5.16 Foreclosure quote and execution



### What the feature does

It calculates an amount to settle the loan early, supersedes previous active
quotes, validates the chosen quote, posts settlement effects, and closes the
loan.

### Why quote and execution are separate

A quote is a time-bound commercial offer/calculation. Execution is a
money-changing command. Separating them allows:

- review before payment,
- idempotent execution,
- expiry,
- recalculation,
- and evidence of what the customer/partner was told.



### Current limitation

The effective date is stored, but pricing is effectively current unpaid
principal plus interest, without true effective-date accrual or expiry.

### Alternatives


| Alternative                                | Use                                 |
| ------------------------------------------ | ----------------------------------- |
| Dynamic quote calculated on every read     | Simple but poor evidence/replay     |
| Persisted versioned quote with expiry/hash | Recommended                         |
| External loan-calculation service          | Useful across many products/systems |
| Manual operations settlement               | Temporary exception, not scalable   |




### Better implementation

Version the calculation policy; include interest cutoff, charges/waiver,
effective date, expiry, and a deterministic quote hash. Execute through the
future receipt/journal layer so reversal/correction is possible.

---



## 5.17 Webhook delivery and redrive



### What the feature does

It reliably informs LSPs about business events even when their endpoint is slow,
down, or returning errors.

### Implementation

- Event added to outbox in the business transaction.
- Worker claims rows with database leasing/`SKIP LOCKED`.
- Request is signed with HMAC.
- Destination is revalidated against SSRF rules at egress.
- Connect/read/body limits are bounded.
- Attempts and response evidence are recorded.
- Retryable outcomes use backoff.
- Permanent/exhausted outcomes dead-letter and alert.
- Admin redrive is capped and audited.



### Why it is strong

It addresses the full delivery lifecycle, not just the happy-path HTTP call.

### Alternatives


| Alternative                    | Assessment                                                         |
| ------------------------------ | ------------------------------------------------------------------ |
| Synchronous webhook in request | Couples partner availability to LMS and risks lost events          |
| Managed queue plus consumers   | Good transport, but still needs transactional publication/evidence |
| CDC outbox publication         | Good at scale; adds connector/platform operations                  |
| Partner polling API only       | Simpler delivery but worse freshness and partner load              |




### Recommendation

Retain. Add per-partner SLOs, lag dashboards, secret rotation, replay windows,
and retention.

### How to defend it

> We do not equate a business commit with a successful partner callback. The
> outbox makes the event durable, and delivery has explicit security, retry,
> dead-letter, redrive, and evidence semantics.

---



## 5.18 Operations alerts and audit explorer



### What the features do

Alerts identify work requiring human attention. Audit explorer gives operations
and reviewers a correlated history across several evidence streams.

### Architectural choice

The platform retains domain-specific audit tables and builds a merged read
model instead of replacing them with one generic event table.

### Why that is reasonable

- Domain tables retain typed evidence.
- Existing transaction boundaries remain simple.
- The explorer can evolve independently.
- A generic event model would risk losing domain constraints.



### Current limitations

- Alert acknowledgement is mutable without a complete action history.
- Assignment, snooze, escalation, close reason, and SLA are limited.
- Sensitive history reads are not universally audited.
- Eight audit streams have different semantics.



### Alternatives


| Alternative                        | Prefer when                                                                 |
| ---------------------------------- | --------------------------------------------------------------------------- |
| Append-only alert action table     | Recommended next step for current system                                    |
| Dedicated case-management platform | Large operations teams and complex SLA/escalation                           |
| Central event store/SIEM           | Cross-system security/audit analysis, not a replacement for domain evidence |




### Recommendation

Keep domain evidence and add append-only alert actions plus a normalized audit
projection/export. Do not turn the entire business system into a generic audit
event store.

---



## 5.19 Dashboard, MIS, and the DWH boundary



### What the features do

- Dashboard gives immediate operational KPIs.
- MIS creates downloadable portfolio reports.
- The DWH spec defines what does not yet exist and prevents ad hoc primary
database access from becoming an accidental architecture.



### Current implementation

- Dashboard aggregates from operational repositories.
- MIS is requested asynchronously, generated in batches, stored in R2, and
downloaded with audit.
- No read replica, CDC publication, warehouse role, or governed analytics API
exists.



### Why this was reasonable

At low volume, a separate analytical platform can cost more than the problem it
solves. Asynchronous report generation protects interactive requests better than
generating large CSVs inline.

### Current weaknesses

- All-time disbursal is labeled MTD.
- DPD90+ is labeled as all overdue.
- Raw borrower name is carried through a “masked” field.
- CSV output is fully materialized in memory.
- Report work lacks robust stale-processing leases.
- Reports contain extensive PII.
- Analytics still competes with the primary database.



### Alternatives


| Alternative                             | When to adopt                                         |
| --------------------------------------- | ----------------------------------------------------- |
| Optimize current queries and stream CSV | Immediate next step                                   |
| PostgreSQL read replica                 | Read/report load begins affecting primary             |
| CDC to warehouse/lakehouse              | Multiple analytical consumers and historical modeling |
| Materialized operational views          | Repeated bounded KPIs need faster reads               |
| Direct analyst access to primary        | Do not adopt                                          |




### How to defend it

> Operational reporting remains intentionally close to the source at current
> scale, while the DWH boundary explicitly prevents uncontrolled analytical
> access. The next evolution is streaming plus a read replica/CDC—not
> microservices.

---



## 5.20 Rate limiting and payload protection



### What the feature does

It constrains selected requests by subject/LSP/application and rejects oversized
or invalid uploads/payloads.

### Why Redis/Bucket4j was chosen

Node-local counters fail when traffic is distributed across multiple
instances. Redis provides one shared limit state.

### Current limitations

- Global fixed partner tiers.
- Many reads are unmetered.
- Missing LSP key resolution can fail open.
- Redis failure policy and observability need explicit production treatment.



### Alternatives


| Alternative                | Use                                                                    |
| -------------------------- | ---------------------------------------------------------------------- |
| API gateway rate limits    | Prefer when gateway reliably knows authenticated tenant and owns plans |
| Database counters          | Strong durability but excessive contention                             |
| In-memory limits           | Development/single-node only                                           |
| Per-LSP plan table + Redis | Recommended when differentiated SLAs become real                       |




### Recommendation

Retain the distributed limiter, fail closed on protected partner paths, measure
degradation, and add per-LSP plans only when contracts demand them.

---



## 5.21 CKYC reporting



### What the intended feature would do

It would assemble regulatory KYC records, create controlled batches, submit them
over an official channel such as SFTP, ingest acknowledgements/rejections, and
support correction/reconciliation.

### Current state

Specified as target state; not implemented.

### Why this should not be “just another file export”

Regulatory submission needs:

- applicable population rules,
- official schema/version,
- maker-checker,
- batch immutability,
- correction/version semantics,
- acknowledgement reconciliation,
- credential/key custody,
- retention,
- and evidence of submission.



### Alternatives

- Build submission internally.
- Use a compliance vendor.
- Generate approved files for controlled manual submission.

The right choice depends on official channel, volume, regulatory ownership, and
vendor availability. There is insufficient evidence to choose today.

---



## 5.22 Three-way ledger and reconciliation



### What the intended feature would do

It would compare:

1. LMS financial intent/state,
2. bank settlement evidence,
3. LSP/collection evidence.

Differences would become classified reconciliation cases rather than silent
balance edits.

### Why three-way reconciliation is necessary

Each source can independently be wrong or delayed:

- LMS may persist an ambiguous result.
- Bank may settle or reverse after the API response.
- LSP may send duplicate or misreferenced collection data.

Two-way comparison cannot identify which side is inconsistent as reliably.

### Recommended implementation

- Immutable journal entries.
- Raw source-file/evidence retention and hashes.
- Normalized bank/LSP transaction staging.
- Deterministic and rule-versioned matching.
- Suspense and exception cases.
- Human resolution with maker-checker.
- Additive corrections.
- Shadow-mode parity before authoritative cutover.



### Why not event-source the whole LMS

A dedicated financial journal provides the accounting properties needed without
forcing every product/configuration/operations feature into an event-sourced
model.

---



## 6. Better implementation opportunities by priority



### Keep the architecture; repair the invariant


| Area                   | Keep                         | Improve                                                   |
| ---------------------- | ---------------------------- | --------------------------------------------------------- |
| One borrower/open loan | Global borrower model        | Borrower-level serialization and DB guard                 |
| Product economics      | Immutable versions           | Effective date and maker-checker                          |
| Tenant isolation       | Principal context + RLS      | PostgreSQL CI and relationship cutover                    |
| Webhooks               | Transactional outbox         | SLOs, retention, secret rotation                          |
| Disbursement           | Durable intent               | Mandatory path, real adapter, approval controls           |
| Repayment              | Locked/idempotent projection | Receipt, allocation, suspense, journal, reversal          |
| Schedule               | Persisted/frozen terms       | Explicit restructure/adjustment model                     |
| Audit                  | Domain evidence              | Append-only action/read history and normalized projection |
| Reporting              | Async object generation      | Streaming, leases, PII minimization, replica/CDC          |




### Do not add complexity until evidence demands it

- Do not split the monolith merely to create service boundaries.
- Do not add Kafka merely because webhooks are asynchronous.
- Do not adopt event sourcing merely because accounting needs a journal.
- Do not add a configurable rules engine for eight stable rules.
- Do not build per-LSP databases while the lender needs global borrowers.
- Do not build a borrower portal without superseding the business model.
- Do not build CKYC before official compliance inputs are owned.



### Add a new architectural component only when the trigger is real


| Component               | Valid trigger                                                             |
| ----------------------- | ------------------------------------------------------------------------- |
| Message broker          | Multiple high-volume independent consumers or worker transport bottleneck |
| Read replica            | Reporting materially affects primary latency/capacity                     |
| CDC/warehouse           | Multiple governed analytical consumers need historical models             |
| External rules engine   | Independent policy ownership/change cadence or models                     |
| Master-party service    | Several products/systems need a shared customer identity                  |
| Dedicated case platform | Alert workflow/SLA becomes operationally complex                          |
| Microservice extraction | Independent scale, availability, ownership, or regulatory boundary        |


---



## 7. How to defend the major architecture choices



### “Why is this a monolith?”

> The lending invariants are transactionally coupled. Approval creates account,
> schedule, audit, and notification state; repayment updates payment,
> installment, account, and partner evidence. One database transaction is safer
> and simpler than distributed compensation at the current team and scale. The
> code is modular, so extraction remains possible when a real independent
> scaling or ownership boundary appears.



### “Why not use microservices for disbursement and payments?”

> Provider integration is already abstracted, and external execution is
> asynchronous. The authoritative intent and financial state benefit from the
> same transactional database. Extracting the connector later is possible, but
> moving the source of truth now would introduce distributed consistency without
> solving a measured problem.



### “Why PostgreSQL RLS?”

> Partner isolation is too important to depend on every query containing the
> correct predicate. Authentication-derived scope and RLS provide independent
> layers. Shared schema remains appropriate because Bhavana needs a global
> borrower and cross-partner risk view.



### “Why a global borrower?”

> Bhavana is the lender and owns consolidated exposure. LSPs are distribution
> channels, so tenant-local duplicates would let one borrower appear unrelated
> across partners. Visibility is tenant-scoped; identity and exposure are global.



### “Why are product versions immutable?”

> Future catalog changes must not rewrite historical loan terms. Versioning
> preserves the exact economics accepted at origination and makes disputes and
> reports reproducible.



### “Why is credit decisioning in code?”

> The current policy is eight deterministic, explainable checks. Code provides
> validation, review, version control, and tests without adding a rules runtime.
> We will externalize it when policy complexity or independent ownership
> justifies the cost.



### “Why use an outbox instead of directly calling the partner?”

> A database commit and a remote HTTP call cannot be one transaction. Direct
> calls either lose events or couple LMS availability to partner availability.
> The outbox makes the event durable and supports retry, dead-letter, redrive,
> and evidence.



### “Why use a disbursement intent?”

> A bank timeout is ambiguous and blind retry can pay twice. The intent persists
> one deterministic transaction reference before the provider call. Unknown
> outcomes are resolved by inquiry and reconciliation rather than sending a new
> payment.



### “Why not accept partial repayments?”

> The current product deliberately narrowed posting to one full installment to
> reduce allocation risk during prototype/UAT. That is not the intended final
> accounting model. Before authoritative collections, we need immutable
> receipts, allocation, suspense, reversal, and reconciliation.



### “Is the platform production ready?”

> The core architecture is production-oriented, but the current implementation
> scope is not live-money complete. It is suitable for synthetic UAT. Live
> operation requires atomic D3 enforcement, provider isolation, maker-checker,
> beneficiary control, real bank integration, financial ledger/reconciliation,
> PII controls, and operational drills.



### “Why should we trust the architecture if controls are missing?”

> Missing controls and a bad foundation are different problems. The platform
> already has the right seams—state machines, version snapshots, idempotency,
> RLS, outbox, intent workflow, adapters, and leased work. The next work adds
> controls at those seams instead of replacing the system.

---



## 8. Statements to avoid

Do not say:

- “The LMS is fully production ready.”
- “RLS guarantees there can never be a tenant leak.”
- “KYC is verified because documents were uploaded.”
- “We cannot make duplicate loans.”
- “The system has an accounting ledger.”
- “All actions are audited.”
- “The dashboard shows MTD disbursement and all overdue loans.”
- “ICICI is integrated.”
- “Mock mode cannot run in production.”
- “The system supports all repayment scenarios.”
- “Microservices would automatically make this more scalable.”

Say instead:

- “The current approved operating scope is synthetic UAT.”
- “Tenant isolation has application and database layers, with production tests
required.”
- “The current KYC gate proves document presence; verification is a future
control.”
- “D3 is implemented semantically and needs atomic concurrency enforcement.”
- “The current payment model is an operational projection; the financial journal
is a launch dependency for collections.”
- “Material mutations have broad evidence coverage; read/action coverage is
being completed.”
- “The provider seam and safe intent workflow exist; the real ICICI adapter and
controls do not yet.”

---



## 9. A guided learning path



### Pass 1 — Learn the business nouns

Be able to explain:

- LSP
- borrower
- product and product version
- application
- account
- schedule/installment
- disbursement intent
- payment transaction
- webhook outbox
- operations alert



### Pass 2 — Learn the three main journeys

1. Partner onboarding and product enablement.
2. Application intake through approval and payout.
3. Repayment through delinquency/foreclosure/closure.



### Pass 3 — Learn the safety patterns

- principal-derived tenant scope,
- RLS,
- idempotency,
- pessimistic/optimistic locking,
- immutable snapshots,
- explicit transitions,
- transaction outbox,
- durable intent,
- status inquiry instead of blind retry,
- leased workers.



### Pass 4 — Learn the product constraints

Memorize D1–D10 and connect each to code behavior.

### Pass 5 — Learn the production gaps

Group them instead of memorizing individual findings:

1. **Atomicity:** concurrent open-loan rule.
2. **Money authorization:** maker-checker, caps, beneficiary mandate.
3. **External rail:** real ICICI and provider isolation.
4. **Accounting:** receipt, journal, reversal, reconciliation.
5. **Privacy/compliance:** masking, encryption, retention, document trust, CKYC.
6. **Operations:** SLOs, DR, capacity, case workflow, test evidence.



### Pass 6 — Practice the counterfactuals

For each design, ask:

- What failure occurs if this is synchronous?
- What history is lost if this record is mutable?
- What leak occurs if tenant scope comes from the request?
- What duplicate occurs without idempotency?
- What ambiguity occurs without an intent/reference?
- What inconsistency occurs if this becomes a separate service?

If you can answer those, you understand the architecture rather than merely
remembering it.

---



## 10. Feature-by-feature review checklist

Use this when reading any implementation or proposing a change.

### Business

- Who initiates the action?
- Who owns the decision?
- Which stakeholder benefits?
- Is the action reversible?
- Can it move money or change contractual terms?
- What evidence is required later?



### API and identity

- Which API/actor may call?
- Is tenant identity derived from authentication?
- Is the command idempotent?
- Are payload limits and validation explicit?



### State and data

- What is the current state?
- What transitions are legal?
- Which records must change atomically?
- What must be immutable or versioned?
- Which database constraint makes the rule real?



### Concurrency

- What happens when two identical commands run?
- What happens when two different commands affect the same borrower/account?
- Is the lock on the actual shared invariant?
- Is there a database backstop?



### External side effects

- Does external work occur inside a transaction?
- What happens after a timeout?
- Is there a deterministic reference?
- Can work be safely retried?
- Is there a status inquiry or reconciliation path?



### Security and compliance

- Which tenant/role can see the data?
- Is sensitive data minimized and masked?
- Are reads/reveals audited?
- What is encrypted?
- What is retained, purged, or legally held?



### Operations

- What alert is raised?
- Can work be reclaimed after a crash?
- Are retries bounded?
- Is manual resolution controlled and audited?
- What metrics/SLOs prove health?



### Alternatives

- Does a new component solve a measured problem?
- Can the invariant be fixed locally?
- What consistency is lost?
- What migration and rollback are required?
- Is the operating team capable of supporting the alternative?

---



## 11. Final position

The most important conclusion is not that the platform is perfect or that every
current implementation should be defended.

The defensible position is more precise:

1. The business model is partner-led lending with lender-controlled risk,
  economics, state, and evidence.
2. The modular monolith is appropriate because the core lending invariants are
  transactionally coupled.
3. The implementation uses several strong production patterns: immutable
  versions, explicit state, RLS, idempotency, transactional outbox, durable
   intents, and leased workers.
4. Several current limitations were reasonable scope constraints for synthetic
  UAT.
5. Those limitations become unacceptable at different launch boundaries.
6. The safest evolution is incremental hardening around existing seams, not a
  platform rewrite.

In one sentence:

> Bhavana LMS has a sound transactional and domain foundation for a
> multi-partner lending platform; it should be retained and hardened with atomic
> credit invariants, live-rail controls, financial accounting, privacy, and
> production operations before broader regulated use.

