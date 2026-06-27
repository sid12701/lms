# Bhawana Loan Management System

## Complete Business Guide

### Workflows, Roles, Rules and Product Decisions

| | |
|:--|:--|
| **Organisation** | Bhawana Capital |
| **Version** | June 2026 |
| **Prepared for** | Business stakeholders, product owners, operations leadership, partner managers, and compliance teams |
| **Classification** | Internal — Business Reference |

---

## Contents

1. What Is the LMS?
2. Everyone Who Uses the System — Roles in Plain Language
3. Loan Products — What Gets Configured
4. The Core Loan Journey
5. Business Logic — How Money and Rules Work
6. Major Product and Policy Decisions
7. Use Cases at a Glance
8. Loan Status — What Each Stage Means
9. What Is Automated vs What Needs People
10. What Is NOT in the System (Out of Scope)
11. Summary

---

# 1. What Is the LMS?

The **Bhawana Loan Management System (LMS)** is Bhawana Capital's central platform for managing loans originated through **Lending Service Providers (LSPs)** — partner companies that bring borrowers and run onboarding on Bhawana's behalf.

| Phase | What happens |
|:------|:-------------|
| **Setup** | Partners, loan products, users, and integrations are configured |
| **Origination** | Partner systems submit applications with borrower details and documents |
| **Credit decision** | System automatically approves or rejects against fixed policy rules |
| **Disbursement** | Funds are sent to the borrower once all prerequisites are met |
| **Servicing** | Installments, payments, overdue tracking, and loan closure |
| **Oversight** | Portfolio dashboard, MIS reports, alerts, and audit trails |

Each partner operates in an **isolated tenant** — they see only their own loans. Bhawana staff see the full portfolio across all partners.

> **Important:** End borrowers do **not** use this system. They deal with the partner; the partner connects to Bhawana through the LMS (API and/or partner staff screens).

# 2. Everyone Who Uses the System — Roles in Plain Language

There are **six human and machine roles**. Below is what each person (or system) is for, what they can touch, and what is off limits.

### System Administrator

**Who:** Bhawana's platform owner — typically IT/operations leadership or a small admin team.

**In one sentence:** Full control of the platform, partners, products, users, loans, disbursement, reporting, and audit.

**Starts on:** Portfolio home screen after sign-in.

**Can do**

- Onboard and disable partners (LSPs)
- Create users, API credentials, and assign roles
- Define loan products and map them to partners
- View portfolio dashboard with KPIs across all partners
- Search all loans and borrowers
- Manually change loan status, override exceptions, trigger disbursement
- Execute foreclosure
- Run MIS reports (preview, download, async)
- Investigate audit explorer
- Redrive failed partner notifications
- Acknowledge alerts

**Cannot do**

- Act as a partner (uses internal screens, not partner API)
- Access borrower-facing mobile apps (none exist)

### Operations User

**Who:** Bhawana's day-to-day loan operations team — triage, support, repayments.

**In one sentence:** Runs the loan queue, researches borrowers, posts repayments, and handles alerts — but cannot change platform configuration or trigger disbursement.

**Starts on:** Loan applications list after sign-in.

**Can do**

- View and search all loan applications and borrowers
- Download KYC documents for review
- Record installment payments
- Acknowledge operational alerts
- Escalate stuck loans to administrators
- View products (read-only)

**Cannot do**

- Access portfolio home dashboard
- Run MIS reports
- Trigger disbursement
- Change loan status (except escalate)
- Manage partners, users, products, or API clients
- Execute foreclosure
- Access audit explorer

> **Note:** The operations screen does **not** show admin lifecycle buttons (approve, disburse, override). Ops staff **escalate** complex cases upward rather than resolving them locally.

### Product Administrator

**Who:** Bhawana's product or credit policy team — defines what loans can be sold.

**In one sentence:** Owns the loan product catalogue (amount limits, rates, fees, tenure) and which partners may offer each product.

**Starts on:** Products page after sign-in.

**Can do:** Create and edit loan products · Map products to partners · View loan list and borrowers (read-only)

**Cannot do:** Post repayments · Change loan status · Trigger disbursement or foreclosure · Manage users, partners, or reports

### LSP Staff — View Only

**Who:** Partner company employee who needs visibility into their pipeline.

**In one sentence:** Read-only window into their own partner's loans.

**Starts on:** My Loans screen after sign-in.

**Can do:** View list and detail of own-tenant loans · See loan status, document checklist, schedule

**Cannot do:** See other partners' data · Create applications · Upload documents · Cancel loans · Access Bhawana internal screens

### LSP Staff — Manage

**Who:** Partner operations staff who support in-flight loans.

**In one sentence:** Can monitor loans, upload documents, verify bank details, and cancel applications before disbursement — but **cannot create** new applications.

**Starts on:** My Loans screen after sign-in.

**Can do:** Everything the view-only role can do · Upload KYC and loan documents · Cancel in-flight applications before disbursement · Run disbursement bank-check from the UI

**Cannot do:** Create loan applications (by design — see Section 6) · Post repayments (partner system does this via API) · Submit repayment schedules via the web UI (API only) · Access Bhawana internal screens

### Partner System

**Who:** The partner's own software — onboarding app, loan origination system, servicing platform.

**In one sentence:** Machine integration that automates the full partner workflow at scale.

**Access:** API only — no web UI.

**Can do:** Create loan applications (only role that can) · Upload documents · Submit repayment schedules · Record payments · Request foreclosure quotes · Run disbursement bank checks · Update borrower bank details · Obtain product catalog · Receive real-time webhook notifications

**Cannot do:** Use any web UI screen · See other partners' data

**Authentication:** Issued API credentials (client ID and secret); optional IP address restrictions per partner.

### The System (Automated)

Background processes with no human involved.

| Automated task | Business purpose |
|:---------------|:-----------------|
| Auto-approval rule evaluation | Instant credit decision against policy |
| Disbursement processing (every ~30 seconds) | Release funds without manual polling |
| Webhook delivery with retries | Keep partner systems in sync |
| MIS report generation (async jobs) | Large exports without blocking users |
| Alert rule evaluation (scheduled) | Surface stuck loans, overdue buckets, spikes |
| First-payment status advance | Move loan from disbursed to under repayment |
| Auto-close on full repayment | Close loan when last installment is paid |

### Role and Screen Quick Reference

| Screen | Admin | Ops | Product Admin | LSP View | LSP Manage |
|:-------|:-----:|:---:|:-------------:|:--------:|:----------:|
| Portfolio home | Yes | — | — | — | — |
| Loan applications | Yes | Yes | Read | — | — |
| Borrowers | Yes | Yes | Read | — | — |
| Alerts | Yes | Yes | — | — | — |
| Reports (MIS) | Yes | — | — | — | — |
| Partners (LSPs) | Yes | — | — | — | — |
| Products | Yes | — | Yes | — | — |
| Users and API clients | Yes | — | — | — | — |
| Audit explorer | Yes | — | — | — | — |
| My Loans | — | — | — | Yes | Yes |

# 3. Loan Products — What Gets Configured

Every loan product defines the **rules of the game** for that loan type. Product administrators (or system administrators) set these values; the system enforces them at origination and servicing.

| Field | What it means | Example |
|:------|:--------------|:--------|
| **Product code** | Short unique identifier (uppercase) | PERSONAL\_24 |
| **Product name** | Display name | Personal Loan 24 months |
| **Minimum principal** | Smallest loan amount allowed | Rs. 50,000 |
| **Maximum principal** | Largest loan amount allowed | Rs. 5,00,000 |
| **Interest rate** | Annual interest rate (percentage) | 18.00% per year |
| **Processing fee rate** | Fee as % of principal (see Section 5.3) | 2.00% |
| **Minimum tenure** | Shortest loan term in months | 6 |
| **Maximum tenure** | Longest loan term in months | 36 |
| **Status** | Active products can be originated; inactive cannot | Active / Inactive |

**Validation rules when creating a product**

- Principal min and max must both be greater than zero; min cannot exceed max.
- Interest rate must be between 0% and 100%.
- Processing fee rate must be between 0% and 100%.
- Tenure min and max must be positive; min cannot exceed max.

**Partner mapping:** A product must be explicitly **mapped and enabled** for a partner before that partner can originate loans against it.

**Interest rate on the loan:** The rate used for EMI and schedule calculation comes from the **product** — not negotiated per application in the current system.

# 4. The Core Loan Journey

**Loan lifecycle (happy path)**

    Partner submits          System checks           Approved?
    application      -->     credit policy     -->   /        \
                                                        |          \
                                                        v            v
                                                Schedule & docs   REJECTED
                                                ready
                                                        |
                                                        v
                                                Funds disbursed
                                                        |
                                                        v
                                                Repayments recorded
                                                        |
                                                        v
                                                  Loan CLOSED

### Step-by-step

| Step | Who | What happens |
|:-----|:----|:---------------|
| 1. Application | Partner system (API) | Borrower details, loan amount, tenure, and product submitted. Borrower identified by PAN — duplicates are linked, not recreated. |
| 2. Documents | Partner staff or system | Eight required document types uploaded (see Section 5.5). |
| 3. Credit decision | System (automatic) | All policy checks run. Pass: approved pending disbursement. Fail: rejected with reasons. |
| 4. Repayment schedule | System auto-generates; partner may replace | Standard EMI schedule created on approval. Partner may submit their own schedule via API before disbursement. |
| 5. Disbursement prep | Partner | Disbursement documents confirmed; bank details verified. |
| 6. Disbursement | System (admin can trigger manually) | Funds sent. Loan becomes active. |
| 7. Servicing | Ops or partner API | Full installment payments recorded in order. |
| 8. Closure | System | All installments paid (fully repaid), or administrator executes foreclosure. |

### Normal end states

| Outcome | When |
|:--------|:-----|
| **Rejected** | Credit policy checks fail |
| **Cancelled (invalid)** | Partner withdraws before disbursement |
| **Disbursement retry** | Temporary payout failure; system retries automatically |
| **Fully repaid** | All installments paid — successful completion |
| **Foreclosed** | Early closure with calculated payoff amount |

# 5. Business Logic — How Money and Rules Work

This section explains the **product and financial decisions** the system applies — what the business has decided and how the maths works.

### Credit Decision — Fully Automatic

> **Decision:** Every application is decided by the **auto-approval rule engine**. There is no human underwriter queue. System administrators can only intervene after the fact via manual status change or override for exceptional cases.

When an application is submitted, the system checks **all** of the following. **Every check must pass** for approval:

| Check | What it means in plain language |
|:------|:------------------------------|
| Product is active | The loan product is turned on |
| Partner is active | The LSP is allowed to operate |
| Product mapped to partner | This partner is allowed to sell this product |
| Loan amount in range | Requested amount is between product min and max principal |
| Tenure in range | Requested months are between product min and max tenure |
| Borrower details complete | Name, PAN, mobile, Aadhaar, full address, positive monthly income, and reference contact must all be present |
| Documents uploaded | All eight required document types are submitted (see Section 5.5) |
| One open loan rule | Borrower does not already have another active loan anywhere on the platform (see Section 5.7) |

**If any check fails:** Application is **rejected** with specific failure reasons.

**Re-evaluation:** When documents are uploaded or data is updated, the system **re-runs** the checks immediately.

### Interest Rate and EMI Calculation

> **Decision:** Interest is quoted as an **annual percentage rate**. Repayments use a standard **monthly EMI (equated monthly installment)** amortisation model.

**Monthly interest rate**

    Monthly rate = Annual rate / 12 / 100

*Example: 18% annual = 1.5% per month on outstanding principal.*

**EMI formula (loans with interest)**

    EMI = Principal x Monthly rate x (1 + Monthly rate)^Tenure
          / ((1 + Monthly rate)^Tenure - 1)

All currency amounts are rounded to **2 decimal places** (paise).

**Zero-interest products:** EMI = Principal / Number of months.

**How each installment is built**

| Step | Calculation |
|:-----|:------------|
| 1 | Opening principal = amount still owed at month start |
| 2 | Interest due = Opening principal x Monthly rate |
| 3 | Principal due = EMI minus Interest due |
| 4 | Installment amount = Principal due + Interest due |
| 5 | Closing principal = Opening principal minus Principal due |

**Last installment:** Any remaining principal is cleared so closing balance is exactly zero. The final EMI may differ slightly from earlier months.

**First payment due date:** One calendar month after **approval date** (not disbursement date).

*Example: Approved 15 January, first due date 15 February.*

**EMI is calculated on** the full approved principal — not net of processing fee (see Section 5.3).

### Processing Fee

**Fee amount**

    Processing fee = Principal x Processing fee rate / 100

(Rounded to 2 decimal places.)

#### Approved business model (Model 1)

> **Decision (documented; implementation in progress):** How processing fees **will work** once fully deployed.

| Aspect | Rule |
|:-------|:-----|
| Cash to borrower | Principal **minus** processing fee |
| Amount borrower repays against | Full **principal** — EMI and interest on full loan amount |
| When fee is taken | At **disbursement** — lender retains fee from payout |
| GST on fee | Not yet in scope — fee is pre-tax |
| Fee waivers / promotions | Not in scope |
| Historical loans | Not backfilled — reports use calculated fee for legacy rows |

**Why this model:** Matches common Indian retail lending — borrower receives slightly less cash than sanctioned amount but repays against full sanctioned principal.

#### Current system behaviour

> **Important:** Today the MIS report **shows** the calculated processing fee, but disbursement **does not yet deduct** the fee. Borrowers currently receive the **full principal**. Do not treat MIS fee figures as proof of actual collection until deduction is live.

**Models considered and rejected**

- **Model 2:** Reduce principal by fee (smaller EMI) — rejected; too much downstream impact.
- **Model 3:** Pay full principal, bill fee separately — rejected; not the chosen market convention.

### Repayment Schedule

**Two ways a schedule gets onto a loan**

| Path | When | Who |
|:-----|:-----|:----|
| Platform-generated | Automatically when loan is approved | System |
| Partner-provided | Partner submits via API, replacing auto-generated schedule | Partner system |

Both paths must pass the **same validation rules** before disbursement.

**When a schedule can be changed**

| Allowed | Not allowed |
|:--------|:------------|
| Replace before disbursement is requested | Change after disbursement |
| Replace while no payments posted | Replace once repayments started |

**Validation rules (all schedules)**

| Rule | Plain language |
|:-----|:---------------|
| Installment count | Must match approved tenure exactly |
| Numbering | 1, 2, 3… consecutively |
| Due dates | Each date strictly after the previous |
| No negatives | All amounts zero or positive |
| Row maths | Principal due + Interest due = Installment amount |
| Principal chain | Each opening principal = previous closing principal |
| First row | Opening principal = approved principal |
| Total principal | Sum of principal-due = approved principal |
| Final row | Closing principal = exactly zero |

### Required Documents

> **Decision:** Eight document types required. **All eight must be uploaded before auto-approval can pass.** For disbursement, documents must be **stored in the LMS**.

| Document | Approval | Disbursement |
|:---------|:--------:|:------------:|
| PAN Card | Yes | Yes |
| Verified Aadhaar File | Yes | Yes |
| Address Proof | Yes | Yes |
| Income Proof | Yes | Yes |
| Bank Statement | Yes | Yes |
| Selfie Photograph | Yes | Yes |
| KFS (Key Fact Statement) | Yes* | Yes |
| Loan Agreement | Yes* | Yes |

*\*In practice all eight are required before approval can complete.*

> **Note:** There is no separate document verification workflow — upload equals submitted. Humans can download documents for review, but the system has no verified/rejected document gate.

### Borrower Identity and Deduplication

> **Decision:** Each person is one borrower record on the platform. Partners submit identity data; the LMS decides whether to reuse or block.

#### How identifiers are normalised before any check

| Field | Normalisation |
|:------|:--------------|
| PAN | Trimmed and converted to **uppercase** |
| Mobile | Trimmed only (digits as submitted) |
| Aadhaar | Trimmed; **spaces removed** |
| Email | Trimmed and lowercased; blank becomes empty |
| Partner loan reference | Trimmed; uniqueness checked **case-insensitively per partner** |

#### Deduplication decision tree (on every new application)

When a partner submits an application, the system runs this sequence **before** the loan is created:

| Step | Lookup | If found | If not found |
|:-----|:-------|:---------|:-------------|
| 1 | PAN (primary key) | Go to step 2 | Go to step 5 |
| 2 | Mobile for same person | If mobile belongs to a **different** borrower than the PAN → **block** (identity conflict) | Continue |
| 3 | Aadhaar on existing PAN record | If stored Aadhaar exists and incoming Aadhaar **differs** → **block** (identity conflict) | Continue |
| 4 | Open loans for this borrower (all partners) | If any open loan exists → **block** (active loan duplicate); ops alert raised | **Reuse** borrower: merge latest profile fields, grant partner visibility, proceed |
| 5 | Mobile without matching PAN | If mobile already belongs to another borrower → **block** (identity conflict) | **Create** new borrower record |

**On identity conflict:** Application is **not** created. Partner receives a conflict error. Bhawana ops receives a **high-severity alert** (`Borrower identity mismatch detected`) with PAN, mobile, Aadhaar, and partner context for manual review.

**On active-loan duplicate:** Application is **not** created. Partner receives `Borrower already has an open loan`. Bhawana ops receives a **high-severity alert** listing every open loan (partner, account status, application id).

**On successful match (same PAN):** Profile fields are updated with the latest submission (name, mobile, email, address, employment, bank details, references, etc.). The partner gains **visibility** to that borrower without creating a duplicate record.

#### Cross-partner visibility

| Who | What they see |
|:----|:--------------|
| Bhawana ops / admin | Borrower across **all** partners |
| Partner staff / API | Only borrowers **linked** to their partner |

#### Required borrower fields (enforced at credit decision)

Full name · PAN · mobile · Aadhaar · address line 1 · city · state · PIN code · monthly income greater than zero · reference person name · reference person number.

#### Partner loan reference deduplication (separate from borrower dedup)

Each partner's own loan reference (`external loan id`) must be **unique within that partner**. A duplicate reference for the same partner is rejected at creation — even if the borrower is different.

### One Open Loan Per Borrower

> **Decision:** A borrower may have **at most one active loan** — checked **across all partners**.

The check runs at **two moments:**

| When | What happens if violated |
|:-----|:-------------------------|
| **Application creation** (borrower dedup) | Application blocked immediately; ops alert |
| **Auto-approval** (credit rule engine) | Application **rejected** with rule `BORROWER_HAS_OPEN_LOAN` |

A loan account counts as **open** when its status is any of:

| Account status | Plain meaning |
|:---------------|:--------------|
| Pending disbursement | Approved; waiting for payout |
| Disbursement requested | Payout call in flight |
| Disbursed | Funds released; servicing active |
| Disbursement pending reconciliation | Payout outcome not yet confirmed |

**Not counted as open:** rejected applications, cancelled applications, closed loans, foreclosed loans, or accounts in disbursement-failed state (the same application may still be in disbursement retry on the application side).

The open-loan lookup always runs on the **admin (cross-tenant) view** so one partner cannot onboard a second loan while another partner still has an active account for the same borrower.

### Disbursement — Gates and Retries

Disbursement **cannot proceed** until:

| Gate | Requirement |
|:-----|:------------|
| Status | Approved pending disbursement, or disbursement retry |
| Documents | All required documents in LMS storage |
| Schedule | Valid repayment schedule on file |
| Bank details | Borrower bank account on file |
| Partner and product | Both still active |

| Mechanism | Detail |
|:----------|:-------|
| Automated processing | Background job checks every ~30 seconds |
| Manual trigger | System administrator can initiate on demand |
| Retries | Temporary failures move to disbursement retry; automatic retries up to configured maximum |
| Stuck alert | Disbursement retry for more than **2 hours** raises an ops alert |

### Repayments and Loan Closure

**Payment rules**

| Rule | Detail |
|:-----|:-------|
| Full installment only | Payment must equal exact outstanding amount of target installment |
| Eligible status | Only after loan is disbursed or under repayment |
| Payment channels | NEFT, RTGS, IMPS, UPI, bank transfer, NACH, cash, cheque |
| Duplicate protection | Idempotency key prevents double-posting |
| Allocation | Payment applies to specified installment; principal and interest tracked per row |

**Status side-effects**

| Event | System behaviour |
|:------|:-----------------|
| First payment | Disbursed → under repayment |
| Last installment paid | Closes as fully repaid; partner notified |
| Every payment | Partner repayment notification (if subscribed) |

### Overdue and Delinquency (Days Past Due)

> **Decision:** Delinquency is **calculated**, not a separate loan status.

| Bucket | Meaning |
|:-------|:--------|
| Current | 0 days past due |
| DPD 1 to 30 | 1 to 30 days overdue |
| DPD 31 to 60 | 31 to 60 days overdue |
| DPD 61 to 90 | 61 to 90 days overdue |
| DPD 90+ | More than 90 days overdue |

Loan bucket = **worst (maximum) days past due** across all installments. Bucket worsening can trigger ops alerts. Portfolio dashboard shows exposure by bucket.

### Foreclosure (Early Loan Closure)

**Foreclosure quote**

| Aspect | Rule |
|:-------|:-----|
| When available | After loan is disbursed |
| Payoff | Unpaid principal + unpaid interest across all installments |
| Effective date | Requester specifies; settlement must be on that exact date |
| New quote | Supersedes any previous active quote |

**Foreclosure execution**

| Aspect | Rule |
|:-------|:-----|
| Who can execute | System administrator; partner via API |
| Settlement amount | Must match quoted payoff exactly |
| Settlement date | Must match quote effective date |
| Reference | Payment reference number mandatory |
| Outcome | Loan foreclosed (terminal); partner notified |

### Partner Cancellation (Invalidation)

> **Decision:** Partners can withdraw **before disbursement only**.

**Allowed statuses:** Submitted, awaiting approval, approved pending disbursement, disbursement retry.

**Reason codes:** Reason A, Reason B, Reason C, or Others (requires explanation text).

# 6. Major Product and Policy Decisions

| # | Decision | What it means for the business |
|:--|:---------|:-------------------------------|
| D1 | Loan origination is API-only for partners | Partners must integrate onboarding software. LSP staff cannot type applications into a web form. |
| D2 | Credit decision is fully automated | No human underwriter queue. Admins handle exceptions only. |
| D3 | One open loan per borrower (cross-partner) | No stacking loans across partners. |
| D4 | Partner deactivation is a kill chain | Disabling a partner revokes API access and tokens immediately. Reactivation requires credential rotation. |
| D5 | Processing fee Model 1 | Net cash to borrower, gross principal for repayment (Section 5.3). |
| D6 | Full installment payments only | No partial EMI. |
| D7 | Schedule frozen after disbursement | Partner cannot change repayment plan once funds are out. |
| D8 | Tenant isolation | Each partner sees only their data; Bhawana sees everything. |
| D9 | Every action is auditable | Status changes, document access, disbursements, logins are logged. |
| D10 | No borrower self-service portal | Borrowers interact through the partner only. |

# 7. Use Cases at a Glance

| Area | Capabilities |
|:-----|:-------------|
| **Access and security** | Sign in · Password change · Session refresh · Partner API auth · Session context |
| **Platform setup** | Register partner · Activate/deactivate · Notifications · IP restrictions · Users · API credentials · Products · Mappings |
| **Origination** | Partner submits loan · Upload documents · Auto credit decision · Cancel before disbursement · Ops document review |
| **Disbursement** | Submit schedule · Bank check · Auto/manual disbursement · Test simulation |
| **Servicing** | Record payment (ops/API) · Foreclosure quote · Foreclosure execution · Update bank details |
| **Operations** | Search borrowers/loans · Dashboard · Alerts · Escalation · Status override |
| **Reporting** | MIS reports · Scheduled alerts · Webhook recovery · Audit search |
| **Partner self-service** | View own loans · View product catalog |

# 8. Loan Status — What Each Stage Means

### Status flow (overview)

The diagram below is **vertical** so every terminal outcome is visible in PDF export.

    SUBMITTED
        |
        v
    AWAITING DECISION -----------------> REJECTED (terminal)
        |
        v
    APPROVED -------------------------> CANCELLED (terminal)
        |
        v
    DISBURSED  <---+
        |         | (disbursement retry loops here)
        v         |
    REPAYING      |
        |         |
        +--------> CLOSED (terminal, fully repaid)
        |
        +--------> FORECLOSED (terminal, early payoff)

| Status (system name) | Business meaning |
|:---------------------|:-----------------|
| Submitted (`INITIALIZED`) | Application created; eight-document checklist seeded; auto-approval not yet run or not yet passed |
| Awaiting decision (`AWAITING_APPROVAL`) | All documents just completed; credit engine evaluating (brief intermediate state) |
| Approved pending disbursement (`APPROVED_PENDING_DISBURSAL`) | Credit passed; loan account and EMI schedule created; waiting for disbursement gates |
| Disbursement retry (`DISBURSEMENT_RETRY`) | Payout failed or needs reconciliation; worker retries until limit; can loop on itself |
| Disbursed (`DISBURSED`) | Funds released; no repayment posted yet (or loan closed directly from here if already settled) |
| Under repayment (`UNDER_REPAYMENT`) | At least one installment payment recorded |
| Rejected (`REJECTED`) | Failed policy or disbursement validation — **terminal** |
| Cancelled (`INVALID`) | Partner withdrew before disbursement — **terminal** |
| Closed (`CLOSED`) | All installments paid — **terminal** |
| Foreclosed (`FORECLOSED`) | Early payoff executed — **terminal** |

**Terminal statuses cannot change again.** Pre-disbursement cancellation (`INVALID`) is allowed from submitted, awaiting decision, approved, or disbursement retry.

### Complete transition reference

Every allowed move is listed below. Anything not listed is **blocked**.

| From | To | Who / what triggers it | Criteria (all must be true unless noted) |
|:-----|:---|:-----------------------|:-------------------------------------------|
| — | **Submitted** | Partner API creates application | Partner active · product active · product mapped and enabled · amount and tenure in product range · interest rate matches product · partner loan reference unique for that partner · borrower dedup passed (Borrower Identity and Deduplication above) |
| Submitted | **Awaiting decision** | System (auto-approval) | All eight intake documents submitted · all auto-approval rules pass (Section 5.1) |
| Submitted | **Cancelled** | Partner (API/UI) or invalidate flow | Application still pre-disbursement · invalidation reason selected (Reason A/B/C or Others with text) |
| Awaiting decision | **Approved** | System (auto-approval) | All auto-approval rules pass · loan account created · platform EMI schedule generated if none exists |
| Awaiting decision | **Rejected** | System (auto-approval) | One or more auto-approval rules fail · rejection records failed rule codes |
| Awaiting decision | **Cancelled** | Partner invalidate | Same as submitted → cancelled |
| Approved | **Disbursed** | Disbursement worker or admin mock resolve | Status approved or retry · loan account exists · all documents in LMS storage · valid repayment schedule · borrower bank on file · bank validation passes · disbursement adapter returns success |
| Approved | **Disbursement retry** | Disbursement outcome | Payout failed or pending reconciliation · **reason code required** · worker retries automatically |
| Approved | **Cancelled** | Partner invalidate | Not yet in servicing |
| Approved | **Rejected** | Disbursement worker only | Automated disbursement validation fails (documents, schedule, bank) — special worker path from approved only |
| Disbursement retry | **Disbursed** | Disbursement worker or admin | Same gates as approved → disbursed |
| Disbursement retry | **Disbursement retry** | Disbursement worker | Retry attempts exhausted — stays in retry (self-loop) · ops alert after **2 hours** |
| Disbursement retry | **Cancelled** | Partner invalidate | Pre-disbursement |
| Disbursed | **Repaying** | System on first payment | First full installment payment successfully posted |
| Disbursed | **Closed** | System on full settlement | Every installment outstanding amount is zero (can close without entering repaying if paid in one shot) |
| Disbursed | **Foreclosed** | Admin or partner API | Active foreclosure quote exists · settlement amount and date match quote exactly · payment reference provided |
| Repaying | **Closed** | System on full settlement | All installments settled |
| Repaying | **Foreclosed** | Admin or partner API | Same foreclosure execution rules as above |

**Auto-approval timing:** The credit engine runs only when the **eighth required document** is submitted in one batch completion event — not on every partial upload. If rules fail while still in submitted, the application **stays submitted** until data improves. If rules fail while in awaiting decision, it moves to **rejected**.

**Manual admin — two paths:**

| Path | What admin can do |
|:-----|:------------------|
| **Manual override** (`manual-status`) | Reset or reject pre-servicing loans only: back to submitted, awaiting decision, disbursement retry, or rejected — with mandatory reason code. Cannot jump to approved, disbursed, repaying, closed, foreclosed, or cancelled. |
| **Standard transition** (`status-transitions`, admin only) | Any transition the state machine allows — including manual **approval** (awaiting decision → approved) if KYC documents are complete. Rule engine is re-evaluated and logged even when admin approves manually. |

Operations users cannot call either API; they escalate to administrators.

### Auto-approval rule codes (awaiting decision → approved or rejected)

| Rule code | Plain-language failure |
|:----------|:-----------------------|
| `PRODUCT_INACTIVE` | Loan product is turned off |
| `LSP_INACTIVE` | Partner is disabled |
| `LSP_PRODUCT_MAPPING_INACTIVE` | Partner is not allowed to sell this product |
| `LOAN_AMOUNT_OUT_OF_RANGE` | Amount below minimum or above maximum |
| `LOAN_TENURE_OUT_OF_RANGE` | Tenure outside product limits |
| `BORROWER_REQUIRED_FIELDS_MISSING` | Any mandatory borrower field blank or income not positive |
| `REQUIRED_DOCUMENTS_NOT_UPLOADED` | One or more of the eight documents not in submitted state |
| `BORROWER_HAS_OPEN_LOAN` | Another open loan account exists for this borrower on another application |

### Status change reason codes (when required)

| Reason code | Used when |
|:------------|:----------|
| `FAILED_VERIFICATION` | Auto-rejection or disbursement-worker rejection |
| `POLICY_EXCEPTION` | Disbursement retry · manual override audit trail |
| `MISSING_DOCUMENTS` | Manual admin transition |
| `BORROWER_CLARIFICATION_REQUIRED` | Manual admin transition |
| `DUPLICATE_APPLICATION` | Manual admin transition |
| `MANUAL_ADMIN_OVERRIDE` | Manual admin transition |

Rejection and disbursement-retry transitions **require** a reason code. Partner cancellation uses separate invalidation reasons (Reason A, B, C, Others with mandatory text for Others).

### Side effects on key transitions

| Transition | System side effects |
|:-----------|:-------------------|
| → Approved | Loan account created (pending disbursement) · EMI schedule auto-generated if missing · partner webhook `LOAN_STATUS_CHANGED` |
| → Disbursed | Loan account marked disbursed · disbursement webhooks · audit event |
| → Repaying | Status webhook only (first payment) |
| → Closed | Loan account closed (fully repaid) · `LOAN_FULLY_REPAID` webhook |
| → Foreclosed | Loan account closed (foreclosure) · foreclosure webhook |
| → Rejected / Cancelled | Partner notified · terminal — no further servicing |

# 9. What Is Automated vs What Needs People

| Automated by the system | Typically needs a person |
|:------------------------|:-------------------------|
| Credit policy checks | Partner and product setup |
| EMI schedule generation on approval | Manual disbursement (optional) |
| Disbursement processing and retries | Exception handling and overrides |
| Partner webhook delivery | Alert acknowledgment |
| Alert generation | Foreclosure execution |
| Loan auto-close on full repayment | MIS interpretation |
| DPD bucket calculation | Escalation of stuck cases |
| First-payment status advance | |

# 10. What Is NOT in the System (Out of Scope)

| Not included | Implication |
|:-------------|:------------|
| Automated credit bureau pull | KYC data from partner submission |
| Direct bank rails (production) | Banking integration is separate delivery |
| Collection agent field tools | No visit/workflow management |
| GST on processing fee | Pending separate decision |
| Fee waivers or zero-fee promotions | Not supported yet |
| Human underwriter workflow | Straight-through unless admin intervenes |
| Ops UI for creating loans | Partner API is origination path |
| Partial installment payments | Full EMI only — by design |

# 11. Summary

| Question | Answer |
|:---------|:-------|
| What does the LMS do? | Originate, approve, disburse, service, and report on partner-originated loans with policy enforcement and audit |
| Who originates loans? | Partner systems via API only |
| How is interest calculated? | Annual rate to monthly EMI amortisation; first due date one month after approval |
| How do processing fees work? | Model 1: fee at disbursement, repayments on full principal (deduction being implemented) |
| Who can post repayments? | Bhawana ops and partner API — full installment only |
| How is overdue tracked? | DPD buckets — not a separate loan status |
| How does a loan close? | Fully repaid or foreclosed |
| What when a partner is disabled? | Immediate API lockout; no new loans |

---

*Where a documented decision is approved but not yet fully implemented (e.g. processing fee deduction), both current and target behaviour are stated explicitly in this guide.*
