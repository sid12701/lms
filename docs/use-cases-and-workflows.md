# Bhawana Loan Management System

## Vendor-Shareable Use Cases and Workflow Document

| Field | Detail |
|:--|:--|
| Organisation | Bhawana Capital |
| Document type | Functional use cases and business workflows |
| Intended audience | Technology vendors, implementation partners, product teams, operations stakeholders |
| Version | June 2026 |

This document describes the required business capabilities, user roles, use cases, workflow rules, and expected outcomes for the Bhawana Loan Management System (LMS).

---

## Contents

1. Document Scope
2. Business Context
3. Roles and Responsibilities
4. Role Access Matrix
5. Loan Product Configuration
6. Business Rules
7. Loan Lifecycle and Status Definitions
8. Use Case Catalogue
9. Detailed Use Cases
10. End-to-End Workflows
11. Reporting, Alerts, Audit, and Notifications
12. Out of Scope
13. Vendor Clarification Points

---

# 1. Document Scope

## 1.1 Scope

This document covers:

- Functional roles required in the LMS.
- Partner onboarding and access management.
- Loan product setup and partner-product mapping.
- Partner-led loan origination.
- Borrower identity, deduplication, and open-loan checks.
- Required document collection.
- Automated credit decisioning.
- Disbursement readiness, initiation, retries, and exception handling.
- Repayment schedule handling.
- Repayment posting and loan closure.
- Foreclosure quote and execution.
- Partner cancellation before disbursement.
- Operational dashboards, alerts, audit trails, and MIS reporting.
- Partner-facing self-service and system-to-system integration expectations.

---

# 2. Business Context

Bhawana Capital works with Lending Service Providers (LSPs), referred to in this document as partners. Partners source borrowers and submit loan applications to Bhawana. Bhawana manages credit policy, disbursement control, servicing oversight, reporting, and compliance visibility.

The LMS is the central operating platform for this lifecycle:

| Phase | Business outcome |
|:--|:--|
| Setup | Partners, users, products, and integration access are configured. |
| Origination | Partner systems submit applications with borrower, product, and document information. |
| Credit decision | Applications are automatically approved or rejected against policy rules. |
| Disbursement | Approved loans are funded after all disbursement gates pass. |
| Servicing | Repayment schedules, payments, overdue tracking, closure, and foreclosure are managed. |
| Oversight | Bhawana monitors portfolio health through dashboards, reports, alerts, and audit trails. |

End borrowers do not directly use the LMS. Borrowers interact with the partner. The partner interacts with Bhawana through partner staff screens and/or system-to-system integration.

Each partner must see only its own loans and borrowers. Bhawana internal users must be able to view and operate across the full portfolio.

---

# 3. Roles and Responsibilities

## 3.1 System Administrator

**Who:** Bhawana platform owner, operations leadership, or designated admin team.

**Purpose:** Full control over platform setup, users, partners, products, loan exceptions, disbursement controls, reporting, audit, and partner notifications.

**Can do:**

- Register, activate, and deactivate partners.
- Configure partner access restrictions and partner notification settings.
- Create and manage internal users, partner users, and partner integration credentials.
- Create and maintain loan products.
- Map loan products to eligible partners.
- View full portfolio dashboard across all partners.
- Search all loan applications and borrowers.
- Review documents and loan details.
- Manually approve eligible exceptions when allowed by policy.
- Override or reset permitted loan statuses with mandatory reason capture.
- Initiate or retry disbursement.
- Execute foreclosure.
- Run MIS reports and download outputs.
- Review audit history.
- Redrive failed partner notifications.
- Acknowledge alerts.

**Cannot do:**

- Create loans as if they were a partner system.
- Use borrower-facing flows, because no borrower portal is in scope.

## 3.2 Operations User

**Who:** Bhawana day-to-day operations team.

**Purpose:** Loan queue monitoring, borrower research, document review, repayment posting, alert handling, and escalation.

**Can do:**

- View and search all loan applications.
- View borrower profiles across partners.
- Download submitted KYC and loan documents for review.
- Record installment payments.
- Acknowledge operational alerts.
- Escalate stuck or exceptional loans to administrators.
- View product information in read-only mode.

**Cannot do:**

- Configure partners, users, API credentials, or products.
- Trigger disbursement.
- Execute foreclosure.
- Run MIS reports unless explicitly granted by business policy.
- Perform admin-level status overrides.
- Access partner-only screens.

## 3.3 Partner Staff - View Only

**Who:** LSP staff who need visibility into their own pipeline.

**Purpose:** Read-only view of that partner's loans.

**Can do:**

- View own partner's loan applications.
- View loan status, document checklist, repayment schedule, and selected borrower details.

**Cannot do:**

- See another partner's data.
- Create applications.
- Upload documents.
- Cancel loans.
- Post repayments.
- Access Bhawana internal screens.

## 3.4 Partner Staff - Manage

**Who:** LSP operations staff supporting active loan processing.

**Purpose:** Monitor in-flight applications for their own partner.

**Can do:**

- Everything Partner Staff - View Only can do.

**Cannot do:**

- Create new loan applications manually through the web UI.
- Post repayments through the web UI.
- Submit repayment schedules through the web UI.
- Access Bhawana internal screens.

## 3.5 Partner System

**Who:** The partner's own loan origination, onboarding, or servicing system.

**Purpose:** Automated partner integration at scale.

**Can do through approved system-to-system channels:**

- Authenticate using partner integration credentials.
- Create loan applications.
- Upload documents.
- Submit or replace repayment schedules before disbursement.
- Run disbursement bank checks.
- Record repayments.
- Request foreclosure quotes.
- Execute foreclosure where permitted by policy.
- Update borrower bank details.
- Retrieve product catalogue and loan status information.
- Receive partner notifications for loan events.

**Cannot do:**

- Access Bhawana internal admin functions.
- Access another partner's data.
- Use human web screens.

## 3.6 Automated System Processes

**Purpose:** Execute rules and operational processing without manual intervention.

**Responsibilities:**

- Evaluate auto-approval rules.
- Generate standard repayment schedules.
- Process disbursement attempts and retries.
- Deliver partner notifications.
- Generate scheduled or asynchronous reports.
- Evaluate alert rules.
- Move a loan into repayment after first payment.
- Close a loan when all installments are fully paid.

---

# 4. Role Access Matrix

| Capability | System Admin | Ops User | Partner View | Partner Manage | Partner System |
|:--|:--:|:--:|:--:|:--:|:--:|
| Partner setup | Yes | No | No | No | No |
| User and credential management | Yes | No | No | No | No |
| Product setup | Yes | No | No | No | Read catalogue |
| Product-partner mapping | Yes | No | No | No | Read eligible products |
| Create loan application | Exception only | No | No | No | Yes |
| View own partner loans | All partners | All partners | Yes | Yes | Yes |
| View all partner loans | Yes | Yes | No | No | No |
| Upload documents | Yes | Yes, if required | No | No | Yes |
| Download documents | Yes | Yes | Own partner only, if allowed | Own partner only, if allowed | Own partner only |
| Auto credit decision | Monitor | Monitor | View outcome | View outcome | Trigger through submission |
| Manual status exception | Yes | Escalate only | No | No | No |
| Cancel before disbursement | Yes | No | No | No | Yes |
| Submit repayment schedule | Yes | No | No | No | Yes |
| Initiate disbursement | Yes | No | No | No | No |
| Record repayment | Yes | Yes | No | No | Yes |
| Foreclosure quote | Yes | View if allowed | View if allowed | View if allowed | Yes |
| Execute foreclosure | Yes | No | No | No | If permitted |
| Reports | Yes | No, unless granted | No | No | No |
| Alerts | Yes | Yes | No | No | No |
| Audit | Yes | No, unless granted | No | No | No |
| Partner notifications | Configure and redrive | View alert impact | No | No | Receive |

---

# 5. Loan Product Configuration

Each loan product defines the commercial and policy boundaries for a loan. A partner may originate only products that are active and explicitly mapped to that partner.

| Product field | Meaning |
|:--|:--|
| Product code | Unique short identifier for the product. |
| Product name | Business display name. |
| Minimum principal | Lowest allowed sanctioned loan amount. |
| Maximum principal | Highest allowed sanctioned loan amount. |
| Annual interest rate | Interest rate used for EMI calculation. |
| Processing fee rate | Fee percentage applied on sanctioned principal. |
| Minimum tenure | Shortest allowed term in months. |
| Maximum tenure | Longest allowed term in months. |
| Status | Whether the product can currently be originated. |

Product validation rules:

- Minimum and maximum principal must be positive.
- Minimum principal cannot exceed maximum principal.
- Interest rate must be within the allowed business range.
- Processing fee rate must be within the allowed business range.
- Minimum and maximum tenure must be positive.
- Minimum tenure cannot exceed maximum tenure.
- Product must be active before it can be used for new applications.
- Product must be enabled for the specific partner before that partner can originate against it.

---

# 6. Business Rules

## 6.1 Loan Origination Channel

Partner-originated loans are created by the partner system through approved system-to-system integration. Partner staff screens are for visibility and pre-disbursement support, not for manually typing new loan applications.

## 6.2 Automated Credit Decision

Every submitted application is evaluated through automated policy checks. There is no separate human underwriter queue in the standard process.

An application can be approved only when all checks pass:

| Check | Business meaning |
|:--|:--|
| Partner active | The originating partner is permitted to operate. |
| Product active | The selected product is available. |
| Product mapped to partner | The partner is allowed to sell that product. |
| Principal in range | Loan amount is within product limits. |
| Tenure in range | Requested term is within product limits. |
| Required borrower fields complete | Mandatory identity, address, income, and reference details are present. |
| Required documents submitted | All eight required document types are uploaded. |
| One open loan rule | Borrower has no other active loan across any partner. |

If any check fails, the application is rejected with clear business reason codes.

## 6.3 Required Borrower Identity Fields

The borrower record must include:

- Full name.
- PAN.
- Mobile number.
- Aadhaar or verified Aadhaar file reference, as applicable.
- Full address.
- Positive monthly income.
- Reference contact information.
- Bank account details before disbursement.

## 6.4 Borrower Deduplication

The platform treats each person as a single borrower across all partners.

Identity checks must follow this business logic:

| Step | Check | Outcome |
|:--|:--|:--|
| 1 | Match by PAN | Reuse borrower if identity is consistent; continue checks. |
| 2 | Compare mobile against existing identity | Block if mobile belongs to a different borrower. |
| 3 | Compare Aadhaar where available | Block if Aadhaar conflicts with the existing borrower. |
| 4 | Check active loans across all partners | Block if borrower already has an open loan. |
| 5 | No existing match | Create a new borrower profile. |

When the same borrower is validly identified by PAN, the latest submitted profile details may update the borrower record, and the originating partner gains visibility to that borrower for its own loan relationship.

When identity conflict is detected, the loan must not be created and Bhawana operations must be alerted for review.

## 6.5 One Open Loan Per Borrower

A borrower may have at most one active loan across all partners.

The rule applies during:

- Application creation.
- Automated credit decision.

Loans counted as open include loans awaiting disbursement, in disbursement processing, disbursed, or pending payout reconciliation.

Rejected, cancelled, fully repaid, and foreclosed loans are not counted as open.

## 6.6 Required Documents

All eight document types are required before automated approval can pass:

| Document | Required for approval | Required for disbursement |
|:--|:--:|:--:|
| PAN Card | Yes | Yes |
| Verified Aadhaar file | Yes | Yes |
| Address proof | Yes | Yes |
| Income proof | Yes | Yes |
| Bank statement | Yes | Yes |
| Selfie photograph | Yes | Yes |
| Key Fact Statement | Yes | Yes |
| Loan agreement | Yes | Yes |

There is no separate document verification status in the standard workflow. Upload means submitted. Bhawana users may download and review documents, but the workflow does not require a separate "verified" gate unless added as a future enhancement.

## 6.7 EMI and Interest

Interest is quoted as an annual percentage rate. Repayments follow a monthly EMI model.

Business rules:

- Monthly rate is derived from annual rate.
- EMI is based on sanctioned principal, monthly rate, and tenure.
- Currency is rounded to two decimal places.
- Zero-interest products divide principal evenly across tenure.
- The last installment may be adjusted slightly to ensure the closing principal becomes zero.
- The first due date is one calendar month after approval unless a partner-provided valid schedule replaces the standard schedule before disbursement.

## 6.8 Processing Fee

The expected business model is:

| Aspect | Rule |
|:--|:--|
| Fee basis | Sanctioned principal multiplied by processing fee rate. |
| Borrower cash received | Sanctioned principal minus processing fee. |
| Repayment basis | Borrower repays the full sanctioned principal. |
| Fee timing | Deducted at disbursement. |
| GST on fee | Out of scope unless separately approved. |
| Fee waivers | Out of scope unless separately approved. |

## 6.9 Repayment Schedule

A repayment schedule can be created in two ways:

| Schedule source | When used |
|:--|:--|
| System-generated schedule | Created automatically after approval. |
| Partner-provided schedule | Submitted by partner system before disbursement and replaces the generated schedule if valid. |

Schedule validation rules:

- Number of installments must match approved tenure.
- Installments must be numbered consecutively.
- Due dates must be strictly increasing **and** satisfy Spec S20 date discipline (first due within the approval window, monthly cadence within tolerance, final due within tenure + horizon grace).
- Amounts cannot be negative.
- Principal plus interest must equal installment amount for each row.
- Opening and closing principal balances must chain correctly.
- First opening principal must equal sanctioned principal.
- Total principal due must equal sanctioned principal.
- Final closing principal must be zero.
- Per-row and total interest must reconcile to the frozen product interest rate / platform generator within configured tolerance (`docs/partner-schedule-validation.md`).

Invalid partner schedules are rejected with `422 REPAYMENT_SCHEDULE_INVALID` (typed `violationType` codes). Prefer `mode: GENERATED` when the platform EMI schedule is acceptable.

A schedule cannot be changed after disbursement or after repayments begin.

## 6.10 Disbursement Gates

Disbursement cannot proceed until:

- Application is approved or in an eligible retry state.
- Partner is active.
- Product is active.
- All required documents are available.
- A valid repayment schedule exists.
- Borrower bank details are present.
- Any required bank confirmation is complete.

Temporary payout failures move the loan to a retry state. Retry failures that remain unresolved beyond the agreed threshold must raise an operations alert.

## 6.11 Payment Rules

- Payments are allowed only after disbursement.
- Payments must match the exact outstanding amount for the target installment.
- Partial installment payments are not supported in the standard workflow.
- Supported payment channels include NEFT, RTGS, IMPS, UPI, bank transfer, NACH, cash, and cheque.
- Duplicate payment posting must be prevented by a unique payment reference or equivalent business control.
- The first payment moves the loan into repayment status.
- The final installment payment closes the loan as fully repaid.

## 6.12 Delinquency

Delinquency is calculated from overdue installments. It is not a separate loan status.

| Bucket | Meaning |
|:--|:--|
| Current | No overdue amount. |
| DPD 1 to 30 | 1 to 30 days past due. |
| DPD 31 to 60 | 31 to 60 days past due. |
| DPD 61 to 90 | 61 to 90 days past due. |
| DPD 90+ | More than 90 days past due. |

The loan's delinquency bucket is based on the worst overdue installment.

## 6.13 Foreclosure

Foreclosure is early closure after disbursement.

Foreclosure quote rules:

- Quote can be requested only after disbursement.
- Payoff equals unpaid principal plus unpaid interest across all installments.
- Quote has an effective settlement date.
- A new quote supersedes any prior active quote.

Foreclosure execution rules:

- Settlement amount must match the active quote.
- Settlement date must match the quote effective date.
- Payment reference is mandatory.
- Loan becomes foreclosed and terminal.
- Partner is notified where notification is configured.

## 6.14 Partner Cancellation

Partners may cancel or invalidate a loan only before disbursement.

Allowed states for partner cancellation:

- Submitted.
- Awaiting decision.
- Approved pending disbursement.
- Disbursement retry.

Cancellation requires a reason. If "Other" is selected, explanation text is mandatory.

---

# 7. Loan Lifecycle and Status Definitions

## 7.1 Lifecycle Overview

| Status | Plain-language meaning |
|:--|:--|
| Submitted | Application created; required data and documents are being checked. |
| Awaiting decision | Application is ready for automated credit decision. |
| Approved pending disbursement | Credit checks passed; disbursement gates must be satisfied. |
| Disbursement retry | Payout attempt failed or needs reconciliation; retry or admin action required. |
| Disbursed | Funds released; no repayment may have been posted yet. |
| Under repayment | At least one installment payment has been recorded. |
| Closed | All installments fully paid; terminal status. |
| Foreclosed | Loan settled early through foreclosure; terminal status. |
| Rejected | Application failed policy or validation checks; terminal status. |
| Cancelled | Partner or admin invalidated the application before disbursement; terminal status. |

## 7.2 Normal Lifecycle Flow

1. Partner system submits loan application.
2. Required documents are collected.
3. Automated policy checks run.
4. Application is either rejected or approved pending disbursement.
5. Repayment schedule is generated or partner-provided schedule is accepted.
6. Disbursement gates are checked.
7. Loan is disbursed.
8. Repayments are recorded.
9. Loan is closed by full repayment or foreclosed by settlement.

## 7.3 Exception Flow

| Scenario | Expected handling |
|:--|:--|
| Identity conflict | Block application and alert Bhawana operations. |
| Borrower already has open loan | Block or reject, depending on point of detection. |
| Required documents missing | Keep application from approval until documents are provided, or reject if decision rules require. |
| Product not active | Reject or block application. |
| Partner not active | Block new partner activity. |
| Disbursement failure | Move to retry; alert if unresolved. |
| Duplicate partner loan reference | Reject duplicate submission. |
| Payment duplicate | Prevent duplicate posting. |

---

# 8. Use Case Catalogue

| ID | Use case | Primary actor |
|:--|:--|:--|
| UC-001 | Sign in | Human user |
| UC-002 | Mandatory password change | Human user |
| UC-003 | End user session | Human user |
| UC-004 | Create partner | System Administrator |
| UC-005 | Activate or deactivate partner | System Administrator |
| UC-006 | Configure partner notification settings | System Administrator |
| UC-007 | Manage partner access restrictions | System Administrator |
| UC-008 | Create internal or partner user | System Administrator |
| UC-009 | Reset password or disable user | System Administrator |
| UC-010 | Create partner integration credential | System Administrator |
| UC-011 | Rotate or disable partner integration credential | System Administrator |
| UC-012 | Create or update loan product | System Administrator |
| UC-013 | Map product to partner | System Administrator |
| UC-014 | Retrieve product catalogue | Partner System |
| UC-015 | Submit loan application | Partner System |
| UC-016 | Run borrower identity and open-loan checks | Automated System |
| UC-017 | Upload required documents | Partner System |
| UC-018 | Review or download documents | Bhawana Admin, Operations User |
| UC-019 | Run automated credit decision | Automated System |
| UC-020 | Cancel pre-disbursement application | Partner System |
| UC-021 | Manually resolve loan exception | System Administrator |
| UC-022 | Submit repayment schedule | Partner System |
| UC-023 | Confirm disbursement readiness | Partner System, System Administrator |
| UC-024 | Initiate disbursement | System Administrator or automated process |
| UC-025 | Process disbursement retry | Automated System, System Administrator |
| UC-026 | Record repayment | Operations User, Partner System |
| UC-027 | Close loan after final repayment | Automated System |
| UC-028 | Request foreclosure quote | System Administrator, Partner System |
| UC-029 | Execute foreclosure | System Administrator, Partner System if permitted |
| UC-030 | Update borrower bank details | Partner System, System Administrator |
| UC-031 | Search loan applications | Bhawana internal users |
| UC-032 | Search and view borrowers | Bhawana internal users |
| UC-033 | View portfolio dashboard | System Administrator |
| UC-034 | Generate MIS report | System Administrator |
| UC-035 | Acknowledge alert | System Administrator, Operations User |
| UC-036 | Escalate loan exception | Operations User |
| UC-037 | Deliver partner notification | Automated System |
| UC-038 | Redrive failed partner notification | System Administrator |
| UC-039 | Search audit history | System Administrator |
| UC-040 | Partner views own loans | Partner Staff |

---

# 9. Detailed Use Cases

## UC-001: Sign In

**Goal:** Allow a human user to access the LMS according to their role.

**Primary actor:** Human user.

**Preconditions:**

- User account exists.
- User account is active.
- Partner user satisfies partner access restrictions where enforced.

**Main flow:**

1. User enters credentials.
2. System verifies credentials and account status.
3. System determines user's role and partner association, if any.
4. User is directed to the correct landing area for their role.

**Exceptions:**

- Invalid credentials: access is denied.
- Inactive user: access is denied.
- Password change required: user is sent to password change flow.
- Partner access restriction failed: access is denied.

**Success outcome:** User enters the LMS with role-appropriate permissions.

## UC-004: Create Partner

**Goal:** Register a new LSP partner for operations.

**Primary actor:** System Administrator.

**Preconditions:**

- Administrator has platform setup rights.
- Required partner business details are available.

**Main flow:**

1. Administrator enters partner name, identifiers, status, contact, and operating details.
2. System creates the partner.
3. Administrator configures products, users, credentials, notifications, and access restrictions as required.

**Success outcome:** Partner is ready for controlled onboarding.

## UC-005: Activate or Deactivate Partner

**Goal:** Control whether a partner may operate.

**Primary actor:** System Administrator.

**Main flow:**

1. Administrator selects partner.
2. Administrator changes status and provides reason where required.
3. If deactivated, new partner activity is blocked and integration access is disabled or restricted according to policy.
4. If reactivated, administrator completes any required credential or configuration steps.

**Success outcome:** Partner operating status matches Bhawana's business decision.

## UC-012: Create or Update Loan Product

**Goal:** Define the loan types partners may offer.

**Primary actor:** System Administrator.

**Main flow:**

1. User enters product code, name, principal range, tenure range, interest rate, processing fee rate, and status.
2. System validates product rules.
3. User saves product.
4. Product becomes available for partner mapping if active.

**Success outcome:** Product can be governed and offered to eligible partners.

## UC-013: Map Product to Partner

**Goal:** Allow a specific partner to originate a specific product.

**Primary actor:** System Administrator.

**Main flow:**

1. User selects partner.
2. User selects product.
3. User enables or disables mapping.
4. System applies mapping during future loan submissions.

**Success outcome:** Partner can originate only approved products.

## UC-015: Submit Loan Application

**Goal:** Allow partner system to originate a loan application.

**Primary actor:** Partner System.

**Preconditions:**

- Partner is active.
- Partner integration access is active.
- Product is active and mapped to partner.
- Application carries a unique partner loan reference.

**Main flow:**

1. Partner system submits borrower details, product selection, requested amount, tenure, bank details if available, and partner loan reference.
2. System validates partner, product, and request completeness.
3. System runs borrower identity and open-loan checks.
4. System creates the application if checks pass.
5. System initializes required document checklist.
6. System evaluates auto-approval once required data and documents are complete.
7. System returns the application status and any rejection reasons.

**Exceptions:**

- Duplicate partner loan reference: submission is rejected.
- Identity conflict: application is blocked and operations alert is raised.
- Borrower has open loan: application is blocked or rejected.
- Product or partner inactive: submission is blocked.

**Success outcome:** Application exists with a clear lifecycle status.

## UC-017: Upload Required Documents

**Goal:** Collect documents required for approval and disbursement.

**Primary actor:** Partner System.

**Main flow:**

1. Actor selects the application.
2. Actor uploads one or more required documents.
3. System marks those document types as submitted.
4. Once all required documents are submitted, system re-evaluates approval readiness.

**Success outcome:** Document checklist is complete and application can proceed to decisioning.

## UC-019: Run Automated Credit Decision

**Goal:** Approve or reject an application based on fixed policy checks.

**Primary actor:** Automated System.

**Preconditions:**

- Application has required borrower data.
- Required documents are submitted.
- Partner and product configuration are valid.

**Main flow:**

1. System checks partner and product eligibility.
2. System checks amount, tenure, borrower details, documents, and open-loan rule.
3. If all checks pass, application becomes approved pending disbursement.
4. If any check fails, application is rejected with reason codes.
5. Partner notification is generated where configured.

**Success outcome:** Application has a final credit decision or a clear reason for rejection.

## UC-020: Cancel Pre-Disbursement Application

**Goal:** Allow partner to withdraw an application before funds are released.

**Primary actor:** Partner System.

**Preconditions:**

- Application has not been disbursed.
- Application is in an allowed pre-disbursement state.

**Main flow:**

1. Actor selects cancellation action.
2. Actor provides reason.
3. System marks application as cancelled.
4. Partner and Bhawana views reflect terminal cancelled status.

**Success outcome:** Application is cancelled and cannot proceed to disbursement.

## UC-021: Manually Resolve Loan Exception

**Goal:** Let administrators handle approved exception scenarios.

**Primary actor:** System Administrator.

**Main flow:**

1. Administrator reviews loan details, documents, history, and alert context.
2. Administrator selects a permitted status action or override.
3. Administrator enters mandatory reason.
4. System records the action and updates the loan if policy permits.

**Success outcome:** Exception is resolved with reason and audit trail.

## UC-022: Submit Repayment Schedule

**Goal:** Let partner provide a custom repayment schedule before disbursement.

**Primary actor:** Partner System.

**Preconditions:**

- Loan is approved but not disbursed.
- No repayment has been posted.

**Main flow:**

1. Partner system submits installment plan.
2. System validates installment count, dates (including S20 window/cadence/horizon), amounts, principal chain, interest vs frozen product rate, and final balance.
3. If valid, schedule replaces the generated schedule.
4. If invalid, schedule is rejected with `422 REPAYMENT_SCHEDULE_INVALID` and clear field violations (see `docs/partner-schedule-validation.md`).

**Success outcome:** Valid repayment schedule is available for disbursement.

## UC-024: Initiate Disbursement

**Goal:** Release funds for an approved loan after all gates pass.

**Primary actors:** System Administrator, Automated System.

**Preconditions:**

- Loan is approved pending disbursement or eligible for retry.
- Required documents are complete.
- Valid repayment schedule exists.
- Borrower bank details are present.
- Partner and product remain active.

**Main flow:**

1. System or administrator starts disbursement processing.
2. System validates all gates.
3. If validation passes, payout is attempted.
4. If payout succeeds, loan becomes disbursed.
5. If payout fails temporarily, loan moves to retry.
6. If validation fails, loan is blocked or rejected according to policy.

**Success outcome:** Loan is funded or placed into a controlled exception state.

## UC-026: Record Repayment

**Goal:** Record full installment payment against a disbursed loan.

**Primary actors:** Operations User, Partner System.

**Preconditions:**

- Loan is disbursed or under repayment.
- Target installment exists and is unpaid.
- Payment amount equals outstanding installment amount.

**Main flow:**

1. Actor submits payment details: installment, amount, date, channel, and reference.
2. System validates eligibility and duplicate controls.
3. System marks installment as paid.
4. If this is the first payment, loan moves to under repayment.
5. If all installments are paid, loan closes automatically.
6. Partner notification is generated where configured.

**Success outcome:** Payment is recorded and loan status reflects servicing progress.

## UC-028: Request Foreclosure Quote

**Goal:** Calculate payoff for early loan closure.

**Primary actors:** System Administrator, Partner System.

**Preconditions:**

- Loan has been disbursed.
- Loan is not already closed or foreclosed.

**Main flow:**

1. Actor requests quote for a specific settlement date.
2. System calculates unpaid principal plus unpaid interest.
3. System returns payoff amount and effective date.
4. Any previous active quote is superseded.

**Success outcome:** Valid foreclosure quote exists.

## UC-029: Execute Foreclosure

**Goal:** Close a loan early using an active foreclosure quote.

**Primary actors:** System Administrator, Partner System if permitted.

**Preconditions:**

- Active quote exists.
- Settlement amount and date match the quote.
- Payment reference is available.

**Main flow:**

1. Actor submits settlement amount, date, and payment reference.
2. System validates against active quote.
3. System marks loan as foreclosed.
4. Partner notification is generated where configured.

**Success outcome:** Loan is terminally foreclosed.

## UC-034: Generate MIS Report

**Goal:** Provide portfolio reporting for management and operations.

**Primary actor:** System Administrator.

**Main flow:**

1. Administrator selects report parameters such as date range, partner, product, and status.
2. System prepares report preview or full export.
3. Administrator downloads report when ready.
4. Report access is recorded for audit purposes.

**Success outcome:** Administrator receives the required MIS output.

## UC-035: Acknowledge Alert

**Goal:** Let operations confirm that an alert has been reviewed.

**Primary actors:** System Administrator, Operations User.

**Main flow:**

1. User opens alert queue.
2. User reviews alert details and related loan or partner context.
3. User acknowledges the alert or escalates as needed.
4. System records the acknowledgement.

**Success outcome:** Alert has a clear operational owner and disposition.

## UC-037: Deliver Partner Notification

**Goal:** Keep partner systems informed about lifecycle events.

**Primary actor:** Automated System.

**Events may include:**

- Loan created.
- Loan status changed.
- Disbursement succeeded or failed.
- Repayment recorded.
- Loan fully repaid.
- Loan foreclosed.
- Cancellation recorded.

**Main flow:**

1. Business event occurs.
2. System prepares partner-specific notification.
3. System sends notification to configured partner destination.
4. If delivery fails temporarily, system retries.
5. If delivery remains failed, administrator can review and redrive.

**Success outcome:** Partner system receives reliable event updates.

## UC-039: Search Audit History

**Goal:** Allow administrators to investigate important actions and changes.

**Primary actor:** System Administrator.

**Main flow:**

1. Administrator selects filters such as user, partner, loan, action type, date range, or status.
2. System returns matching audit entries.
3. Administrator reviews history for compliance, investigation, or reconciliation.

**Success outcome:** Bhawana can reconstruct who did what, when, and why.

---

# 10. End-to-End Workflows

## WF-01: Partner Onboarding

**Objective:** Make a new partner operational.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | System Administrator | Register partner. |
| 2 | System Administrator | Configure partner status and operating details. |
| 3 | System Administrator | Create partner users. |
| 4 | System Administrator | Create partner integration credentials. |
| 5 | System Administrator | Map eligible products to partner. |
| 6 | System Administrator | Configure notifications and access restrictions. |
| 7 | Partner | Validate integration and operational readiness. |
| 8 | System Administrator | Activate partner for production use. |

**Exit criteria:** Partner can originate eligible products and receive event updates.

## WF-02: Product Setup and Partner Mapping

**Objective:** Make a product available for controlled origination.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | System Administrator | Define product code and name. |
| 2 | System Administrator | Configure principal, tenure, interest, and processing fee. |
| 3 | System Administrator | Activate product when approved. |
| 4 | System Administrator | Map product to approved partners. |
| 5 | System | Enforce product and mapping during application submission. |

**Exit criteria:** Only mapped active partners can originate the product.

## WF-03: Loan Origination and Auto-Approval

**Objective:** Originate a partner loan and produce an automated decision.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | Partner System | Submit application with borrower, product, amount, tenure, and partner reference. |
| 2 | System | Validate partner, product, and uniqueness. |
| 3 | System | Run borrower identity and open-loan checks. |
| 4 | Partner System | Upload all required documents. |
| 5 | System | Run automated credit decision. |
| 6A | System | Approve application pending disbursement if all rules pass. |
| 6B | System | Reject application with reason codes if any rule fails. |
| 7 | System | Notify partner of outcome where configured. |

**Exit criteria:** Application is approved pending disbursement, rejected, or blocked with clear reason.

## WF-04: Document Completion

**Objective:** Ensure application has all required documents.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | System | Presents required document checklist. |
| 2 | Partner System | Uploads documents. |
| 3 | System | Marks each document type submitted. |
| 4 | System | Checks whether all eight required documents are present. |
| 5 | System | Re-evaluates credit decision when the checklist is complete. |
| 6 | Bhawana Ops | May download and review documents for operational purposes. |

**Exit criteria:** Document checklist complete and loan can proceed according to policy.

## WF-05: Disbursement

**Objective:** Fund approved loans only after all required gates pass.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | System | Confirms loan is approved pending disbursement. |
| 2 | System | Verifies documents, schedule, bank details, partner status, and product status. |
| 3 | System Administrator or System | Initiates disbursement. |
| 4A | System | Marks loan disbursed on successful payout. |
| 4B | System | Moves loan to retry if payout temporarily fails. |
| 5 | System | Generates partner notification where configured. |
| 6 | Operations | Reviews alerts for stuck or repeated failures. |

**Exit criteria:** Loan is disbursed or in a controlled retry/exception state.

## WF-06: Repayment Servicing and Closure

**Objective:** Track installment payments until the loan is closed.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | System | Maintains repayment schedule. |
| 2 | Operations User or Partner System | Posts full installment payment. |
| 3 | System | Validates amount, installment, status, and duplicate controls. |
| 4 | System | Marks installment paid. |
| 5 | System | Moves loan to under repayment on first payment. |
| 6 | System | Closes loan automatically when final installment is paid. |
| 7 | System | Notifies partner where configured. |

**Exit criteria:** Loan remains current, overdue, or closes after full repayment.

## WF-07: Foreclosure

**Objective:** Close a loan early with a calculated settlement amount.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | System Administrator or Partner System | Requests quote for settlement date. |
| 2 | System | Calculates payoff amount. |
| 3 | Actor | Confirms payment amount, date, and reference. |
| 4 | System | Validates against active quote. |
| 5 | System | Marks loan foreclosed. |
| 6 | System | Notifies partner where configured. |

**Exit criteria:** Loan is foreclosed and terminal.

## WF-08: Partner Cancellation Before Disbursement

**Objective:** Allow partner to withdraw an application before funds are released.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | Partner System | Selects eligible application. |
| 2 | Partner System | Provides cancellation reason. |
| 3 | System | Confirms application has not been disbursed. |
| 4 | System | Marks application cancelled. |
| 5 | System | Notifies partner and updates Bhawana view. |

**Exit criteria:** Application is terminally cancelled.

## WF-09: Alert Handling and Escalation

**Objective:** Ensure operational exceptions are visible and acted upon.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | System | Creates alert for configured exception. |
| 2 | Operations User | Reviews alert details. |
| 3 | Operations User | Resolves directly if within permission. |
| 4 | Operations User | Escalates to administrator if admin action is required. |
| 5 | System Administrator | Takes exception action where needed. |
| 6 | User | Acknowledges alert. |

**Exit criteria:** Alert is acknowledged, escalated, or resolved.

## WF-10: MIS Reporting

**Objective:** Generate portfolio reports for management, operations, and reconciliation.

| Step | Owner | Action |
|:--|:--|:--|
| 1 | System Administrator | Selects report filters. |
| 2 | System | Shows preview or accepts full export request. |
| 3 | System | Generates report. |
| 4 | System Administrator | Downloads report when ready. |
| 5 | System | Records report access. |

**Exit criteria:** Report is available and access is auditable.

---

# 11. Reporting, Alerts, Audit, and Notifications

## 11.1 Portfolio Dashboard

System Administrator should be able to view portfolio metrics such as:

- Applications by status.
- Disbursed amount.
- Outstanding exposure.
- Overdue buckets.
- Partner-level performance.
- Recent applications.
- Open operational alerts.

## 11.2 MIS Reports

MIS reporting should support:

- Date filters.
- Partner filters.
- Product filters.
- Status filters.
- Preview before download where practical.
- Export for offline analysis.
- Access history for compliance.

## 11.3 Alerts

The system should generate alerts for important operational conditions, including:

- Borrower identity conflict.
- Borrower already has an active loan.
- Stuck disbursement retry.
- Disbursement failure.
- Partner auto-rejection spike.
- Overdue bucket worsening.
- Failed partner notification.
- Operations escalation.
- Partner disabled.

## 11.4 Audit Requirements

The system must retain audit history for sensitive and important actions:

- Sign in and security events.
- User and credential changes.
- Partner status changes.
- Product changes.
- Loan application lifecycle changes.
- Document access.
- Disbursement attempts and outcomes.
- Repayment postings.
- Foreclosure execution.
- Report downloads.
- Partner notification retries and redrives.

Each audit entry should capture who acted, what changed, when it happened, business reason where applicable, and the related partner or loan context.

## 11.5 Partner Notifications

Partners should receive notifications for relevant loan events. Notifications must be partner-scoped and retryable.

Expected notification events:

- Loan created.
- Loan status changed.
- Loan approved.
- Loan rejected.
- Loan cancelled.
- Disbursement succeeded.
- Disbursement failed or retried.
- Repayment recorded.
- Loan fully repaid.
- Foreclosure quote generated.
- Loan foreclosed.

Administrators must be able to review failed notifications and retry them when appropriate.

---

# 12. Out of Scope

The following are not part of the standard LMS workflow unless separately approved:

| Out-of-scope item | Implication |
|:--|:--|
| Borrower self-service portal | Borrowers interact through partners. |
| Manual partner web form for loan creation | Partner systems originate loans. |
| Human underwriter queue | Credit decisioning is automated, with admin exceptions only. |
| Automated credit bureau pull | Partner-submitted data is used for decisioning unless future scope changes. |
| Collection agent field workflow | No field-visit or collection-agent task management. |
| Partial EMI payments | Full installment payment only. |
| GST on processing fee | Requires separate policy and product decision. |
| Fee waivers or promotional fee rules | Requires separate product decision. |
| Post-disbursement schedule edits | Schedule is frozen after disbursement. |

---

# 13. Vendor Clarification Points

The vendor should explicitly confirm these points during discovery:

1. Final list of required borrower fields and document metadata.
2. Exact processing fee treatment at disbursement, including tax treatment if any.
3. Whether partner foreclosure execution is allowed for all partners or only selected partners.
4. Whether operations users should remain escalation-only for lifecycle exceptions.
5. Whether document upload should remain "submitted only" or needs a future verified/rejected review stage.
6. Notification event list and payload fields expected by partners.
7. Report formats, filters, and delivery expectations.
8. Alert thresholds for rejection spikes, stuck disbursement, and overdue movement.
9. Partner access restriction policy by environment and partner type.
10. Data retention and audit retention requirements.

---

End of document.
