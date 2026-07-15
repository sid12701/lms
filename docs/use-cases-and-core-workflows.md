# Use Cases and Core Workflows

## 1. Document Purpose

This document is a simplified, business-facing version of the complete LMS use case and workflow documentation. It focuses on **what the system does for users and partners**—use cases, workflows, actors, and successful outcomes—without technical implementation detail. It is intended for product managers, business teams, stakeholders, and anyone who needs a clear view of how the Bhawana Loan Management System (LMS) supports the loan lifecycle across Bhawana internal staff and Lending Service Provider (LSP) partners.

---

## Table of Contents

1. [Document Purpose](#1-document-purpose)
2. [Actors](#2-actors)
3. [Use Cases](#3-use-cases)
4. [Workflows](#4-workflows)
5. [High-Level Business Process Flow](#5-high-level-business-process-flow)
6. [Summary](#6-summary)

**Use case groups:** [Authentication](#31-authentication-and-access) · [Platform admin](#32-platform-and-tenant-administration) · [Users & API clients](#33-user-and-api-client-management) · [Products](#34-product-catalog) · [Origination](#35-loan-origination-and-credit-decision) · [Documents](#36-document-management) · [Status admin](#37-loan-status-administration) · [Disbursement](#38-disbursement) · [Servicing](#39-loan-servicing-and-repayment) · [Operations](#310-operations-and-portfolio-visibility) · [Reporting](#311-reporting) · [Alerting](#312-alerting) · [Webhooks](#313-webhooks-and-partner-notifications) · [Audit](#314-audit-and-compliance) · [LSP self-service](#315-lsp-self-service)

**Workflow groups:** [Onboarding](#41-platform-onboarding-and-access) · [Origination](#42-loan-origination) · [Disbursement](#43-disbursement) · [Servicing](#44-loan-servicing-and-closure) · [Operations](#45-operations-and-portfolio-management) · [Reporting](#46-reporting) · [Alerting](#47-alerting-and-escalation) · [Webhooks](#48-webhooks-and-partner-sync) · [Audit](#49-audit-and-compliance) · [LSP UI](#410-lsp-self-service)

---

## 2. Actors

### System Administrator
- **Role in the system:** Full platform control across all LSP tenants.
- **Key actions:** Onboard LSPs and users, configure loan products and LSP product mappings, manage integrations, approve or override loan decisions, initiate disbursement, manage reporting and audit, handle webhook redrive, execute foreclosure.

### Operations User
- **Role in the system:** Day-to-day loan operations and monitoring.
- **Key actions:** Triage loan queue, research borrowers, post repayments, acknowledge alerts, escalate stuck loans to administrators.

### LSP UI User (Read)
- **Role in the system:** Partner staff with view-only access to their tenant's loans.
- **Key actions:** View own-tenant loan applications and status.

### LSP UI User (Write)
- **Role in the system:** Partner staff who can manage in-flight loans for their tenant.
- **Key actions:** View loans, upload KYC documents, invalidate pre-disbursal applications.

### LSP API Client
- **Role in the system:** Partner system integrating via machine credentials.
- **Key actions:** Obtain access tokens, create loan applications, upload documents, submit repayment schedules, run bank checks, record payments, request foreclosure quotes, update borrower bank details.

### System
- **Role in the system:** Automated processing without human intervention.
- **Key actions:** Run auto-approval rules, process disbursements, dispatch webhooks, generate reports, evaluate alert rules.

### External LSP Webhook Consumer
- **Role in the system:** Partner system receiving real-time event notifications from LMS.
- **Key actions:** Receive and process loan lifecycle events (created, status changed, disbursement, repayment, etc.).

---

## 3. Use Cases

Use cases are grouped by business area and ordered to follow the platform lifecycle: **access → setup → origination → disbursement → servicing → operations → platform services**.

### Use Case Index

| ID | Use Case |
|----|----------|
| UC-001 | User Login |
| UC-002 | Mandatory Password Change |
| UC-003 | Session Refresh and Logout |
| UC-004 | API Client Token Issuance |
| UC-005 | View Session Context |
| UC-006 | Create LSP Tenant |
| UC-007 | Activate or Deactivate LSP |
| UC-008 | Configure LSP Webhook Subscription |
| UC-009 | Manage LSP IP Allowlists |
| UC-010 | Create Internal or LSP User |
| UC-011 | Update User or Reset Password |
| UC-012 | Create API Client |
| UC-013 | Rotate API Client Secret |
| UC-014 | Create or Update Loan Product |
| UC-015 | Configure Product–LSP Mappings |
| UC-016 | LSP API — Create Loan Application |
| UC-017 | Operations — Create Loan Application |
| UC-018 | Auto-Approval Rule Evaluation |
| UC-019 | Upload KYC Documents (LSP) |
| UC-020 | Download KYC Documents (Operations) |
| UC-021 | Manual Status Transition |
| UC-022 | Manual Status Override |
| UC-023 | Invalidate Loan (Pre-Disbursal) |
| UC-024 | Submit Repayment Schedule |
| UC-025 | Disbursement Bank Check |
| UC-026 | Initiate Disbursement |
| UC-027 | Automated Disbursement Processing |
| UC-028 | Simulate Disbursement Outcome |
| UC-029 | Record Payment (Internal Operations) |
| UC-030 | Record Payment (LSP API) |
| UC-031 | Request Foreclosure Quote |
| UC-032 | Execute Foreclosure |
| UC-033 | Update Borrower Bank Details |
| UC-034 | Search and View Borrowers |
| UC-035 | View Portfolio Dashboard |
| UC-036 | Search Loan Applications |
| UC-037 | Portfolio MIS — Preview and Sync Download |
| UC-038 | Async MIS Report Generation |
| UC-039 | Acknowledge Operations Alert |
| UC-040 | Escalate Loan to Administrator |
| UC-041 | Scheduled Alert Rule Evaluation |
| UC-042 | Webhook Outbox Dispatch |
| UC-043 | Manual Webhook Redrive |
| UC-044 | Audit Explorer Search |
| UC-045 | Auth Audit Search |
| UC-046 | LSP View Own Loans |
| UC-047 | View LSP Product Catalog |

---

### 3.1 Authentication and Access

### UC-001: User Login

**Objective:**  
Allow a human user to securely sign in and access the parts of the system appropriate to their role.

**Primary Actor:**  
Any human user (System Administrator, Operations User, or LSP UI User).

**Supporting Actors:**  
Authentication service.

**Trigger:**  
User submits username and password on the login page.

**Preconditions:**  
- User account exists and is active.
- For LSP UI users, access is from an allowed location when IP restrictions are enforced.

**Core Flow:**
1. User enters username and password.
2. System validates credentials and account status.
3. System establishes a session and directs the user to their role-appropriate home page.
4. If a mandatory password change is required, user is directed to change password first (see UC-002).

**Success Outcome:**  
User is signed in and can access permitted features for their role.

---

### UC-002: Mandatory Password Change

**Objective:**  
Ensure users update their password when required before using the system.

**Primary Actor:**  
Authenticated user.

**Supporting Actors:**  
None.

**Trigger:**  
User signs in and the system flags that a password change is required.

**Preconditions:**  
- User has successfully authenticated.
- Password change is mandated for this account.

**Core Flow:**
1. User is redirected to the change-password screen.
2. User submits a new password meeting policy requirements.
3. System updates the password and clears the change-required flag.
4. User proceeds to their normal landing page.

**Success Outcome:**  
Password is updated; user can access the full application.

---

### UC-003: Session Refresh and Logout

**Objective:**  
Keep active sessions usable over time and allow users to end their session securely.

**Primary Actor:**  
Any signed-in human user.

**Supporting Actors:**  
Authentication service.

**Trigger:**  
User’s session nears expiry (refresh) or user chooses to sign out (logout).

**Preconditions:**  
User has an active session.

**Core Flow:**
1. **Refresh:** System renews the session without requiring re-entry of credentials.
2. **Logout:** User selects sign out; system invalidates the session.

**Success Outcome:**  
Session remains valid (refresh) or is cleanly terminated (logout).

---

### UC-004: API Client Token Issuance

**Objective:**  
Allow partner systems to authenticate for automated API access.

**Primary Actor:**  
LSP API Client.

**Supporting Actors:**  
Authentication service.

**Trigger:**  
Partner system requests an access token using client credentials.

**Preconditions:**  
- API client is active.
- LSP tenant is active.
- IP allowlist requirements are satisfied when enforced.

**Core Flow:**
1. Partner system submits client ID and secret.
2. System validates credentials and client status.
3. System issues a short-lived access token scoped to the partner’s tenant.

**Success Outcome:**  
Partner system holds a valid token and can call LSP APIs.

---

### UC-005: View Session Context

**Objective:**  
Let a signed-in user see who they are and what tenant/role context applies.

**Primary Actor:**  
Any authenticated human user.

**Supporting Actors:**  
None.

**Trigger:**  
User opens the application or a context-aware screen.

**Preconditions:**  
User is signed in.

**Core Flow:**
1. Application requests current session context.
2. System returns user identity, role(s), and LSP association (if applicable).

**Success Outcome:**  
User and application share a clear view of current permissions and tenant scope.

---

### 3.2 Platform and Tenant Administration

### UC-006: Create LSP Tenant

**Objective:**  
Register a new Lending Service Provider as an isolated tenant on the platform.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator creates a new LSP from the LSP administration area.

**Preconditions:**  
Administrator is signed in with full admin rights.

**Core Flow:**
1. Administrator enters LSP details (name, identifiers, configuration).
2. System creates the tenant record.
3. Tenant is available for further setup (users, API clients, products, webhooks).

**Success Outcome:**  
New LSP tenant exists and can be configured for operations.

---

### UC-007: Activate or Deactivate LSP

**Objective:**  
Control whether an LSP tenant and its integrations may operate on the platform.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
Alert service (on deactivation).

**Trigger:**  
Administrator changes LSP status to active or inactive.

**Preconditions:**  
LSP tenant exists.

**Core Flow:**
1. Administrator selects activate or deactivate and provides a reason when required.
2. System updates LSP status.
3. On deactivation, associated API clients are deactivated and partner operations are blocked.

**Success Outcome:**  
LSP status reflects business intent; inactive LSPs cannot originate or process new partner activity as designed.

---

### UC-008: Configure LSP Webhook Subscription

**Objective:**  
Define where and how the partner receives real-time loan lifecycle notifications.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator configures or updates webhook settings for an LSP.

**Preconditions:**  
LSP tenant exists.

**Core Flow:**
1. Administrator sets webhook endpoint URL and related subscription settings.
2. System validates and saves configuration.
3. Future domain events for that LSP are queued for delivery to the endpoint.

**Success Outcome:**  
Partner is configured to receive webhook notifications for configured event types.

---

### UC-009: Manage LSP IP Allowlists

**Objective:**  
Restrict partner API and UI access to approved network locations when required.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator adds, updates, or removes IP allowlist entries for API and/or UI access.

**Preconditions:**  
LSP tenant exists.

**Core Flow:**
1. Administrator maintains allowlist entries for API access and/or UI access.
2. Administrator enables or disables enforcement per surface.
3. System applies restrictions on subsequent partner API and UI sign-in attempts.

**Success Outcome:**  
IP restrictions match the organization’s security policy for that LSP.

---

### 3.3 User and API Client Management

### UC-010: Create Internal or LSP User

**Objective:**  
Provision human users for Bhawana staff or partner staff with appropriate roles.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator creates a new user account.

**Preconditions:**  
Administrator has user-management rights; for LSP users, target LSP exists.

**Core Flow:**
1. Administrator enters user details, role(s), and LSP association if applicable.
2. System creates the account (often with initial password or change-required flag).
3. User can sign in per UC-001.

**Success Outcome:**  
New user exists with correct role and tenant scope.

---

### UC-011: Update User or Reset Password

**Objective:**  
Maintain user records and recover access when credentials must be reset.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator updates profile/status or resets a user’s password.

**Preconditions:**  
Target user exists.

**Core Flow:**
1. Administrator updates user attributes (status, roles, etc.) or initiates password reset.
2. System persists changes.
3. User signs in with updated credentials or status.

**Success Outcome:**  
User record reflects current business state; user can access the system as permitted.

---

### UC-012: Create API Client

**Objective:**  
Issue machine credentials so a partner system can integrate with the LSP API.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator creates an API client for an LSP.

**Preconditions:**  
LSP tenant is active or being onboarded.

**Core Flow:**
1. Administrator defines API client for the LSP.
2. System generates client credentials (shown once for secure storage by partner).
3. Partner uses credentials per UC-004.

**Success Outcome:**  
Partner has active API client credentials for integration.

---

### UC-013: Rotate API Client Secret

**Objective:**  
Replace API client secrets periodically or after compromise without unnecessary downtime.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator rotates secret for an existing API client.

**Preconditions:**  
API client exists.

**Core Flow:**
1. Administrator initiates secret rotation.
2. System issues a new secret (with grace period for partner cutover when applicable).
3. Partner updates integration to use the new secret.

**Success Outcome:**  
API client remains active with a new secret; partner completes cutover.

---

### 3.4 Product Catalog

### UC-014: Create or Update Loan Product

**Objective:**  
Define loan products with terms, bounds, and business rules used during origination.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator creates or edits a loan product.

**Preconditions:**  
User has administrator rights.

**Core Flow:**
1. User defines product name, amount/tenure/rate bounds, and related attributes.
2. System saves the product as active or inactive.
3. Product is available for LSP mapping (UC-015) and origination when active and mapped.

**Success Outcome:**  
Loan product is defined and ready for partner assignment.

---

### UC-015: Configure Product–LSP Mappings

**Objective:**  
Control which loan products each LSP may offer to end borrowers.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator maps or unmaps products to an LSP.

**Preconditions:**  
Product and LSP exist.

**Core Flow:**
1. Administrator selects product(s) for an LSP and enables or disables mapping.
2. System saves mapping.
3. Partner origination (API or ops) may only use active products mapped to that LSP.

**Success Outcome:**  
Each LSP has a clear, enforceable product catalog for intake.

---

### 3.5 Loan Origination and Credit Decision

### UC-016: LSP API — Create Loan Application

**Objective:**  
Enable partner-led loan origination at scale without manual data entry by operations.

**Primary Actor:**  
LSP API Client.

**Supporting Actors:**  
Auto-approval engine, borrower service, webhook dispatcher.

**Trigger:**  
Partner system submits a new loan application with borrower and loan details.

**Preconditions:**  
- API client and LSP are active.
- Product is active and mapped to the LSP.
- Valid API token; IP allowlist satisfied when enforced.

**Core Flow:**
1. Partner authenticates and submits application (with optional duplicate-request protection).
2. System validates payload, product mapping, and business rules.
3. System resolves or creates borrower identity (deduplicated by PAN).
4. System creates loan application and document checklist.
5. System records intake snapshot and moves application to awaiting approval.
6. System runs automated credit decision (UC-018).
7. On approval: application moves to approved pending disbursal and loan account is created.
8. On rejection: application moves to rejected with reason codes.
9. Partner receives application response; lifecycle events are queued for webhooks.

**Success Outcome:**  
Application exists in approved-pending-disbursal, rejected, or other valid pre-disbursal state; partner is notified of material status changes.

---

### UC-017: Operations — Create Loan Application

**Objective:**  
Allow Bhawana staff to manually enter a loan application when partner API intake is not used.

**Primary Actor:**  
System Administrator or Operations User.

**Supporting Actors:**  
Auto-approval engine, webhook dispatcher.

**Trigger:**  
Staff submits a new loan application from the operations console.

**Preconditions:**  
- User has ops loan creation rights.
- Active product mapped to target LSP.

**Core Flow:**
1. Staff enters borrower and loan details equivalent to partner API intake.
2. System creates application, checklist, and intake record.
3. System transitions to awaiting approval and runs auto-approval (UC-018).
4. Outcome follows same approval/rejection paths as partner API intake.

**Success Outcome:**  
Manually entered application reaches the same decision states as API-submitted applications.

---

### UC-019: Upload KYC Documents (LSP)

**Objective:**  
Submit required KYC and loan documents so applications can pass approval and disbursement gates.

**Primary Actor:**  
LSP API Client or LSP UI User (Write).

**Supporting Actors:**  
Document storage service.

**Trigger:**  
Partner uploads one or more documents for a loan application.

**Preconditions:**  
Application exists for the partner’s tenant; document type is on the checklist.

**Core Flow:**
1. Actor selects document type and uploads file.
2. System stores document and marks checklist item as submitted.
3. Updated checklist supports auto-approval and disbursement validation.

**Success Outcome:**  
Required documents are on file; checklist reflects submitted status.

---

### UC-018: Auto-Approval Rule Evaluation

**Objective:**  
Apply consistent, automated credit policy without manual underwriter for straight-through processing.

**Primary Actor:**  
System (auto-approval engine).

**Supporting Actors:**  
Operations alert service.

**Trigger:**  
Application enters awaiting approval after intake.

**Preconditions:**  
Application is in awaiting approval status.

**Core Flow:**
1. Engine verifies product, LSP, and mapping are active.
2. Engine validates loan amount, tenure, and rate within product bounds.
3. Engine validates required borrower fields are present.
4. Engine verifies approval-required documents are submitted.
5. Engine checks borrower has no other open loan across all LSPs.
6. If all checks pass → approved pending disbursal and loan account created.
7. If any check fails → rejected with structured reason codes.

**Success Outcome:**  
Application is either approved pending disbursal or rejected with documented reasons.

---

### UC-023: Invalidate Loan (Pre-Disbursal)

**Objective:**  
Allow partners to withdraw in-flight applications before funds are disbursed.

**Primary Actor:**  
LSP API Client or LSP UI User (Write).

**Supporting Actors:**  
Lifecycle service, webhook dispatcher.

**Trigger:**  
Partner selects invalidate with a business reason.

**Preconditions:**  
Application is in a pre-disbursal status (initialized, awaiting approval, approved pending disbursal, or disbursement retry).

**Core Flow:**
1. Actor selects invalidation reason (standard codes or “other” with explanation).
2. System validates pre-disbursal status.
3. System moves application to invalid (terminal); loan account mirrored if it exists.
4. Partner is notified via status-change webhook.

**Success Outcome:**  
Application is terminally invalid; partner systems reflect cancellation.

---

### 3.6 Document Management

### UC-020: Download KYC Documents (Operations)

**Objective:**  
Allow authorized Bhawana staff to review borrower documents for operational and compliance needs.

**Primary Actor:**  
System Administrator or Operations User.

**Supporting Actors:**  
Document storage service.

**Trigger:**  
Staff requests download of a KYC document from loan detail.

**Preconditions:**  
Document exists on the application; user has ops access.

**Core Flow:**
1. Staff selects document from loan application detail.
2. System authorizes access and serves the document.
3. Document access is recorded for compliance traceability.

**Success Outcome:**  
Staff obtains the document needed for review; access is logged.

---

### 3.7 Loan Status Administration

### UC-021: Manual Status Transition

**Objective:**  
Allow administrators to move a loan through governed lifecycle states when business judgment requires.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
Lifecycle service, webhook dispatcher.

**Trigger:**  
Administrator initiates an allowed status transition from the loan detail action bar.

**Preconditions:**  
Application is in a state that permits the requested transition.

**Core Flow:**
1. Administrator selects target status and provides required justification where needed.
2. System validates transition against the state machine.
3. System updates application (and loan account if applicable), records audit, and notifies partner via webhook when applicable.

**Success Outcome:**  
Application is in the new valid status with audit trail and partner notification as appropriate.

---

### UC-022: Manual Status Override

**Objective:**  
Resolve exceptional cases by overriding status when standard transitions are insufficient.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
Lifecycle service.

**Trigger:**  
Administrator performs manual status override with reason code.

**Preconditions:**  
Administrator has override privilege.

**Core Flow:**
1. Administrator selects override target status and reason.
2. System applies override, bypassing normal transition path where permitted.
3. Change is audited with reason code.

**Success Outcome:**  
Application reflects administratively corrected status for exceptional handling.

---

### 3.8 Disbursement

### UC-024: Submit Repayment Schedule

**Objective:**  
Provide installment plan required before disbursement and for servicing.

**Primary Actor:**  
LSP API Client.

**Supporting Actors:**  
None.

**Trigger:**  
Partner submits repayment schedule for an approved application.

**Preconditions:**  
Application is in a state that accepts schedule upload (typically approved pending disbursal).

**Core Flow:**
1. Partner sends installment schedule (`mode: GENERATED` or `LSP_PROVIDED` with due dates and amounts).
2. System validates principal integrity and Spec S20 date/interest rules (see `docs/partner-schedule-validation.md`); rejects with `422 REPAYMENT_SCHEDULE_INVALID` when invalid.
3. Schedule becomes available for disbursement validation and future payment allocation.

**Success Outcome:**  
Repayment schedule is on file; disbursement prerequisites can be satisfied.

---

### UC-025: Disbursement Bank Check

**Objective:**  
Pre-validate borrower bank details before disbursement to reduce mismatches and failures.

**Primary Actor:**  
LSP API Client.

**Supporting Actors:**  
None.

**Trigger:**  
Partner requests bank verification for an application.

**Preconditions:**  
Application has borrower bank details; appropriate pre-disbursal status.

**Core Flow:**
1. Partner triggers bank check.
2. System compares or validates bank details against disbursement requirements.
3. Mismatches are logged for operations follow-up.

**Success Outcome:**  
Bank readiness is confirmed or mismatch is flagged before disbursement.

---

### UC-033: Update Borrower Bank Details

**Objective:**  
Keep disbursement and servicing bank information current.

**Primary Actor:**  
LSP API Client or System Administrator.

**Supporting Actors:**  
Borrower service, webhook dispatcher.

**Trigger:**  
Actor submits updated bank details for a borrower.

**Preconditions:**  
Borrower exists; actor authorized for tenant.

**Core Flow:**
1. Actor submits new bank account information.
2. System validates and updates borrower record.
3. Change is audited; partner may receive notification event.

**Success Outcome:**  
Borrower bank details are updated for subsequent disbursement and verification.

---

### UC-026: Initiate Disbursement

**Objective:**  
Allow an administrator to manually trigger disbursement when not relying solely on automated processing.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
Disbursement service, adapter.

**Trigger:**  
Administrator requests disbursement from loan detail.

**Preconditions:**  
Application approved pending disbursal; prerequisites (documents, schedule, bank details) met.

**Core Flow:**
1. Administrator initiates disbursement.
2. System requests funds transfer via disbursement channel.
3. Outcome updates loan account and application status (disbursed, retry, or failure paths).

**Success Outcome:**  
Disbursement is requested; application progresses toward active loan or defined failure/retry state.

---

### UC-027: Automated Disbursement Processing

**Objective:**  
Reliably disburse approved loans without manual polling or constant ops intervention.

**Primary Actor:**  
System.

**Supporting Actors:**  
Disbursement adapter, webhook dispatcher, alert service.

**Trigger:**  
Scheduled automated processing finds applications ready for disbursement.

**Preconditions:**  
Automated processing is enabled; application in approved pending disbursal or disbursement retry; prerequisites met.

**Core Flow:**
1. System identifies eligible applications.
2. System validates documents, schedule, and bank details.
3. System initiates disbursement and invokes disbursement channel.
4. On success → disbursed, then under repayment; partner notified.
5. On retryable failure → disbursement retry (up to configured limit).
6. On exhaustion or validation failure → rejected or ops alert raised.

**Success Outcome:**  
Funds disbursed and loan enters repayment phase, or failure is clearly surfaced for intervention.

---

### UC-028: Simulate Disbursement Outcome

**Objective:**  
Support non-production testing by simulating disbursement success or failure outcomes.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
Disbursement service.

**Trigger:**  
Administrator submits mock disbursement outcome in test environments.

**Preconditions:**  
Mock/simulation capability enabled; application in disbursement pipeline.

**Core Flow:**
1. Administrator selects simulated outcome (success or failure type).
2. System applies outcome as if from disbursement channel.
3. Application and loan account update accordingly.

**Success Outcome:**  
Loan reaches disbursed, retry, or failure state for testing without real fund movement.

---

### 3.9 Loan Servicing and Repayment

### UC-029: Record Payment (Internal Operations)

**Objective:**  
Record borrower repayments against installments from the operations console.

**Primary Actor:**  
System Administrator or Operations User.

**Supporting Actors:**  
Repayment service, webhook dispatcher.

**Trigger:**  
Staff records a payment on the loan schedule tab.

**Preconditions:**  
Loan is disbursed or under repayment; schedule has pending installments.

**Core Flow:**
1. Staff opens loan schedule and enters payment amount, date, and reference.
2. System validates full installment amount is paid (partial installments are not accepted).
3. System records payment and allocates to the next pending installment.
4. On first payment, loan may advance from disbursed to under repayment.
5. When all installments are paid, loan closes as fully repaid.
6. Partner is notified of repayment and full repayment events when applicable.

**Success Outcome:**  
Payment is recorded; schedule and loan status are updated; partner notified.

---

### UC-030: Record Payment (LSP API)

**Objective:**  
Allow partners to post repayments via integration for servicing automation.

**Primary Actor:**  
LSP API Client.

**Supporting Actors:**  
Repayment service, webhook dispatcher.

**Trigger:**  
Partner submits payment via LSP loan payment API.

**Preconditions:**  
Loan in servicing-eligible status; schedule exists.

**Core Flow:**
1. Partner submits payment with amount, date, and reference (with duplicate protection).
2. System applies same validation and allocation rules as internal ops payment.
3. Schedule and status update; webhooks fire as in UC-029.

**Success Outcome:**  
Partner-posted payment is reflected in LMS servicing records.

---

### UC-031: Request Foreclosure Quote

**Objective:**  
Provide payoff amount and terms before early loan closure via foreclosure.

**Primary Actor:**  
System Administrator or LSP API Client.

**Supporting Actors:**  
Foreclosure service.

**Trigger:**  
Actor requests foreclosure quote for an active loan.

**Preconditions:**  
Loan is in a status eligible for foreclosure quote.

**Core Flow:**
1. Actor requests quote.
2. System calculates payoff amount and quote validity per business rules.
3. Quote is returned and stored for subsequent execution.

**Success Outcome:**  
Valid foreclosure quote is available for decision and execution.

---

### UC-032: Execute Foreclosure

**Objective:**  
Close a loan early through foreclosure when business terms are met.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
Foreclosure service, webhook dispatcher.

**Trigger:**  
Administrator executes foreclosure against a valid quote or eligible loan.

**Preconditions:**  
Loan eligible; quote valid if required.

**Core Flow:**
1. Administrator confirms foreclosure execution.
2. System applies foreclosure, updates loan and application to foreclosed (terminal).
3. Partner is notified of foreclosure completion.

**Success Outcome:**  
Loan is terminally foreclosed; balances and status reflect closure.

---

### 3.10 Operations and Portfolio Visibility

### UC-034: Search and View Borrowers

**Objective:**  
Enable operations and admin staff to research borrower identity across the portfolio.

**Primary Actor:**  
System Administrator or Operations User.

**Supporting Actors:**  
None.

**Trigger:**  
Staff searches or opens borrower directory.

**Preconditions:**  
User has borrower read access.

**Core Flow:**
1. Staff enters search criteria (e.g., PAN, name).
2. System returns matching borrowers and LSP access relationships.
3. Staff views borrower profile and linked loans.

**Success Outcome:**  
Staff has the borrower context needed for triage and support.

---

### UC-035: View Portfolio Dashboard

**Objective:**  
Give leadership a high-level view of portfolio health and volume.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator opens home/dashboard.

**Preconditions:**  
User has dashboard access.

**Core Flow:**
1. Administrator navigates to portfolio overview.
2. System presents aggregated metrics (counts, statuses, trends as configured).

**Success Outcome:**  
Administrator sees current portfolio snapshot for decision-making.

---

### UC-036: Search Loan Applications

**Objective:**  
Find and triage loan applications across filters relevant to operations.

**Primary Actor:**  
Internal roles (System Administrator, Operations User).

**Supporting Actors:**  
None.

**Trigger:**  
User opens loan applications list and applies filters.

**Preconditions:**  
User has loan list access.

**Core Flow:**
1. User sets filters (status, LSP, date range, etc.).
2. System returns matching applications.
3. User opens detail for investigation or action.

**Success Outcome:**  
User locates target applications for workflow continuation.

---

### UC-039: Acknowledge Operations Alert

**Objective:**  
Track that ops or admin staff have seen and are handling operational alerts.

**Primary Actor:**  
System Administrator or Operations User.

**Supporting Actors:**  
None.

**Trigger:**  
User acknowledges an alert from the alerts console.

**Preconditions:**  
Alert exists and is open.

**Core Flow:**
1. User reviews alert detail.
2. User marks alert as acknowledged.
3. Alert status updates for team visibility.

**Success Outcome:**  
Alert is acknowledged; team coordination improves.

---

### UC-040: Escalate Loan to Administrator

**Objective:**  
Route stuck or complex loans from operations to administrators for intervention.

**Primary Actor:**  
Operations User.

**Supporting Actors:**  
Alert service.

**Trigger:**  
Ops user escalates a loan from loan detail.

**Preconditions:**  
User has ops access; loan requires admin attention.

**Core Flow:**
1. Ops user selects escalate and provides context.
2. System creates escalation alert for administrators.
3. Administrators triage from alerts or loan queue.

**Success Outcome:**  
Loan is flagged for administrator action; escalation is visible in ops alerting.

---

### 3.11 Reporting

### UC-037: Portfolio MIS — Preview and Sync Download

**Objective:**  
Obtain management information system (MIS) portfolio report immediately for a date range.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
Reporting service.

**Trigger:**  
Administrator requests preview or synchronous download on reports page.

**Preconditions:**  
User has report access.

**Core Flow:**
1. Administrator selects LSP filter and disbursal date range.
2. System generates MIS dataset.
3. Administrator previews or downloads report file directly.

**Success Outcome:**  
MIS report is available for analysis without waiting for background processing.

---

### UC-038: Async MIS Report Generation

**Objective:**  
Generate large portfolio MIS exports without blocking the user interface.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
System, file storage, email notification (optional).

**Trigger:**  
Administrator submits async report request.

**Preconditions:**  
User has report access.

**Core Flow:**
1. Administrator defines report parameters and submits request.
2. System queues report job.
3. System generates file and stores it.
4. Administrator is notified when complete (optional email).
5. Administrator downloads completed report.

**Success Outcome:**  
Large MIS CSV is generated, stored, and available for download.

---

### 3.12 Alerting

### UC-041: Scheduled Alert Rule Evaluation

**Objective:**  
Proactively detect operational issues (stuck loans, spikes, delinquency transitions, etc.).

**Primary Actor:**  
System.

**Supporting Actors:**  
None.

**Trigger:**  
Scheduled evaluation of configured alert rules.

**Preconditions:**  
Alert rules are configured and automated processing is enabled.

**Core Flow:**
1. System evaluates rules against current portfolio and integration health.
2. Matching conditions create or update ops alerts.
3. Staff act on alerts per UC-039 and related workflows.

**Success Outcome:**  
Operational risks surface as actionable alerts before they become crises.

---

### 3.13 Webhooks and Partner Notifications

### UC-042: Webhook Outbox Dispatch

**Objective:**  
Deliver partner notifications reliably with retries when endpoints fail temporarily.

**Primary Actor:**  
System.

**Supporting Actors:**  
External LSP webhook consumer.

**Trigger:**  
Domain event enqueues webhook; system processes pending outbox entries.

**Preconditions:**  
LSP webhook endpoint configured; event in outbox.

**Core Flow:**
1. System claims pending webhook events.
2. System delivers signed payload to partner endpoint.
3. On success → marked delivered.
4. On transient failure → retryable failure and retry.
5. On persistent failure → permanent failure for admin redrive (UC-043).

**Success Outcome:**  
Partner receives event, or failure is classified for retry or manual redrive.

---

### UC-043: Manual Webhook Redrive

**Objective:**  
Recover partner notifications that reached permanent delivery failure after automated retries.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
System.

**Trigger:**  
Administrator redrives failed webhook event from admin console.

**Preconditions:**  
Event in permanent failure state; redrive attempts within allowed limit.

**Core Flow:**
1. Administrator selects failed event and initiates redrive.
2. System resets event for dispatch and records redrive audit.
3. System attempts delivery again.

**Success Outcome:**  
Event is re-queued and successfully delivered, or failure reason is updated for further action.

---

### 3.14 Audit and Compliance

### UC-044: Audit Explorer Search

**Objective:**  
Investigate activity across loan, user, product, disbursement, and related domains in one place.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator searches audit explorer with filters.

**Preconditions:**  
User has audit access.

**Core Flow:**
1. Administrator sets filters (domain, date, correlation ID, entity, etc.).
2. System returns unified timeline across audit streams.
3. Administrator traces related events for investigation.

**Success Outcome:**  
Administrator completes forensic review with cross-domain visibility.

---

### UC-045: Auth Audit Search

**Objective:**  
Review sign-in and authentication-related activity for security and support.

**Primary Actor:**  
System Administrator.

**Supporting Actors:**  
None.

**Trigger:**  
Administrator searches authentication audit (API available; **Needs Clarification** — no dedicated UI documented in source).

**Preconditions:**  
User has appropriate admin access.

**Core Flow:**
1. Administrator queries auth events with filters.
2. System returns login success/failure and related auth events.

**Success Outcome:**  
Authentication activity is visible for security review.

---

### 3.15 LSP Self-Service

### UC-046: LSP View Own Loans

**Objective:**  
Give partner staff visibility into their tenant’s loan pipeline.

**Primary Actor:**  
LSP UI User (Read or Write).

**Supporting Actors:**  
None.

**Trigger:**  
Partner user opens My Loans workspace.

**Preconditions:**  
User is signed in with LSP UI role; scoped to own tenant.

**Core Flow:**
1. User views list of tenant loan applications.
2. User opens detail for status, checklist, and permitted actions.

**Success Outcome:**  
Partner staff understand status of their loan book without cross-tenant access.

---

### UC-047: View LSP Product Catalog

**Objective:**  
Let partners see which loan products they may originate.

**Primary Actor:**  
LSP API Client or LSP UI User.

**Supporting Actors:**  
None.

**Trigger:**  
Partner requests product catalog for their tenant.

**Preconditions:**  
LSP is active; mappings exist.

**Core Flow:**
1. Partner requests catalog.
2. System returns active products mapped to that LSP with relevant terms/bounds.

**Success Outcome:**  
Partner systems and staff originate loans only against valid, assigned products.

---

## 4. Workflows

Workflows are grouped by business phase and numbered sequentially. Each entry preserves the full sequence from the source documentation in business language.

### 4.1 Platform Onboarding and Access

### Workflow 1: LSP Tenant Onboarding

**Purpose:**  
Bring a new partner live on the platform end-to-end.

**Actors Involved:**  
System Administrator, LSP API Client (post-setup), LSP UI Users (post-setup).

**Workflow Steps:**
1. Administrator creates LSP tenant record.
2. Administrator activates LSP when ready.
3. Administrator configures webhook subscription and IP allowlists as required.
4. Administrator creates API client and internal/LSP users.
5. System administrator maps active products to LSP.
6. Partner integrates API and/or assigns UI users.
7. Partner can originate loans per Workflow 1.

**Final Outcome:**  
LSP is operational with users, credentials, products, and notifications configured.

---

### Workflow 2: LSP Deactivation Kill Chain

**Purpose:**  
Safely stop a partner from new activity when relationship or risk requires shutdown.

**Actors Involved:**  
System Administrator, System.

**Workflow Steps:**
1. Administrator deactivates LSP with documented reason.
2. System deactivates associated API clients.
3. New loan origination and partner API activity are blocked.
4. Ops alert may be raised for LSP disabled.
5. **Needs Clarification:** Behavior of in-flight loans at deactivation time should be confirmed with business.

**Final Outcome:**  
Inactive LSP cannot start new partner operations; platform risk is contained.

---

### Workflow 3: User and Access Provisioning

**Purpose:**  
Establish human and machine access for Bhawana and partner organizations.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. Administrator creates internal user, LSP UI user, or API client as needed.
2. Administrator assigns roles and LSP scope.
3. For API clients, credentials are securely shared with partner once.
4. Users sign in (UC-001); partners obtain tokens (UC-004).
5. Password reset or secret rotation performed when required.

**Final Outcome:**  
Authorized actors can access only their permitted surfaces and tenants.

---

### Workflow 4: Human Authentication Lifecycle

**Purpose:**  
Secure sign-in, password maintenance, and session management for all human users.

**Actors Involved:**  
All human users, System.

**Workflow Steps:**
1. User signs in with username and password.
2. If password change required → user completes mandatory password change before full access.
3. During session, system refreshes access as needed.
4. User signs out to end session.
5. Failed sign-in attempts are subject to rate limiting; LSP UI may be blocked by IP policy.

**Final Outcome:**  
Users maintain secure, role-appropriate access throughout their session.

---

### Workflow 5: API Client Authentication

**Purpose:**  
Enable partner systems to obtain and use machine credentials.

**Actors Involved:**  
LSP API Client, System Administrator (setup).

**Workflow Steps:**
1. Administrator creates API client for LSP.
2. Partner stores client ID and secret securely.
3. Partner requests access token using client credentials.
4. Partner calls LSP APIs with token until expiry, then renews.
5. Administrator rotates secret when policy or incident requires.

**Final Outcome:**  
Partner integration authenticates reliably with tenant isolation.

---

### Workflow 6: Product Catalog Configuration

**Purpose:**  
Define and assign loan products partners may sell.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. System administrator creates or updates loan product with terms and bounds.
2. Administrator maps product to one or more LSPs.
3. Only active, mapped products appear in partner catalog and pass origination validation.

**Final Outcome:**  
Enforceable product catalog aligned with credit policy per LSP.

---

### Workflow 7: Onboarding and Authentication (Swimlane)

**Purpose:**  
Establish identity before any loan or admin work.

**Actors Involved:**  
System Administrator, all human users, LSP API Client.

**Workflow Steps:**
1. User or API client account is created by administrator.
2. Actor signs in (human) or obtains token (machine).
3. Session context confirms role and tenant scope.
4. Actor proceeds to origination, operations, or admin modules.

**Final Outcome:**  
Authenticated, scoped access to permitted capabilities.

---

### 4.2 Loan Origination

### Workflow 8: Partner API Loan Origination (Straight-Through)

**Purpose:**  
Partner submits a loan via API and receives an automated credit decision without manual operations intervention.

**Actors Involved:**  
LSP API Client, System (auto-approval engine, webhook dispatcher).

**Workflow Steps:**
1. Partner system obtains API access token.
2. Partner submits loan application with borrower, product, amount, tenure, and document metadata.
3. System validates partner identity, tenant scope, and business rules.
4. System creates or links borrower and creates loan application with document checklist.
5. System records intake snapshot.
6. System moves application from initialized to awaiting approval.
7. Auto-approval engine evaluates all rules and passes.
8. System approves application pending disbursal and creates loan account.
9. System queues loan created and status changed notifications for partner.
10. Partner receives success response with application ID and status.
11. System delivers webhook notifications to partner endpoint.

**Final Outcome:**  
Application is approved pending disbursal; partner is notified; loan is ready for disbursement prerequisites and processing.

---

### Workflow 9: Partner API Loan Origination (Auto-Rejection)

**Purpose:**  
Handle partner-submitted applications that fail automated credit policy checks.

**Actors Involved:**  
LSP API Client, System (auto-approval engine).

**Workflow Steps:**
1. Partner submits loan application (steps 1–6 same as Workflow 8).
2. Auto-approval engine evaluates rules; one or more checks fail (e.g., amount out of bounds, incomplete documents, borrower has open loan).
3. System moves application to rejected with structured rejection reasons.
4. Partner receives response with rejection detail.
5. Status change notification is queued for partner.

**Final Outcome:**  
Application is terminally rejected with documented reasons; partner can correct and resubmit as a new application per business rules.

---

### Workflow 10: Partner API Loan Origination (Existing Borrower Link)

**Purpose:**  
Originate a loan for a borrower who already exists in the global identity registry.

**Actors Involved:**  
LSP API Client, System.

**Workflow Steps:**
1. Partner submits application with borrower PAN matching an existing record.
2. System links application to existing borrower rather than creating duplicate identity.
3. Intake and auto-approval proceed as in Workflow 8 or 9.
4. Borrower profile may be updated with any new permissible fields from the submission.

**Final Outcome:**  
Single borrower identity maintained across LSPs; new application proceeds under correct borrower record.

---

### Workflow 11: Operations Manual Loan Intake

**Purpose:**  
Create loan applications from operations console when partner API is not used.

**Actors Involved:**  
System Administrator or Operations User.

**Workflow Steps:**
1. Staff enters loan and borrower data in operations console.
2. System creates application, checklist, and intake record.
3. Auto-approval runs (Workflow 1 or 2 outcome).
4. **Needs Clarification:** Source notes no dedicated UI create dialog in frontend—workflow may be API-only today.

**Final Outcome:**  
Manually captured application follows same decision and lifecycle paths as API intake.

---

### Workflow 12: Automated Credit Decision Evaluation

**Purpose:**  
Apply rule engine at awaiting approval without manual underwriter queue.

**Actors Involved:**  
System (auto-approval engine).

**Workflow Steps:**
1. Application enters awaiting approval after intake.
2. Engine checks product/LSP/mapping active status.
3. Engine validates amount, tenure, rate, borrower fields, documents, and open-loan policy.
4. Pass → approved pending disbursal; fail → rejected with reason codes.
5. Ops alerts may fire on certain failure patterns (e.g., auto-reject spike).

**Final Outcome:**  
Straight-through credit decision recorded before disbursement phase.

---

### Workflow 13: LSP UI Document Upload and Invalidation

**Purpose:**  
Enable partner operators to complete document requirements or cancel in-flight loans from My Loans.

**Actors Involved:**  
LSP UI User (Write).

**Workflow Steps:**
1. User opens My Loans and selects a loan.
2. User reviews status and document checklist.
3. **Upload path:** User uploads missing KYC documents; checklist items mark as submitted.
4. **Invalidation path:** User opens mark invalid dialog, selects reason, and confirms.
5. System moves application to invalid (terminal) and notifies partner systems via webhook.

**Final Outcome:**  
Documents submitted for approval/disbursement gates, or application is partner-cancelled before disbursement.

---

### Workflow 14: LSP-Initiated Pre-Disbursal Invalidation (API)

**Purpose:**  
Allow partner systems to cancel in-flight applications programmatically.

**Actors Involved:**  
LSP API Client, System (lifecycle service).

**Workflow Steps:**
1. Partner calls invalidate with reason code (and detail text when “other”).
2. System validates application is pre-disbursal.
3. System transitions to invalid; loan account updated if present.
4. Status change webhook is queued.

**Final Outcome:**  
Application terminally invalid; partner integration reflects withdrawal.

---

### Workflow 15: Origination Swimlane

**Purpose:**  
End-to-end intake from application creation through credit decision.

**Actors Involved:**  
LSP API Client, Operations User, LSP UI User (Write), System.

**Workflow Steps:**
1. Create application (API, ops, or **Needs Clarification** for full ops UI).
2. Upload required documents.
3. Auto-approval evaluates and decides.
4. Outcome: approved pending disbursal, rejected, or invalid (partner cancel).

**Final Outcome:**  
Origination phase complete with decision recorded.

---

### Workflow 16: Loan Lifecycle — Rejection Path

**Purpose:**  
Terminal handling of applications failing credit policy at approval stage.

**Actors Involved:**  
System (auto-approval engine), LSP partner (notification).

**Workflow Steps:**
1. Application reaches awaiting approval.
2. Auto-approval fails one or more rules.
3. Application moves to rejected (terminal).
4. Partner notified via webhook and API status.

**Final Outcome:**  
Application closed as rejected with auditable reasons.

---

### Workflow 17: Loan Lifecycle — Partner Pre-Disbursal Cancellation

**Purpose:**  
Partner withdraws application before funds are released.

**Actors Involved:**  
LSP API Client or LSP UI User (Write).

**Workflow Steps:**
1. Partner invalidates from initialized, awaiting approval, approved pending disbursal, or disbursement retry.
2. Application and loan account (if any) move to invalid (terminal).
3. Webhook notifies partner systems.

**Final Outcome:**  
Application cancelled; no disbursement occurs.

---

### 4.3 Disbursement

### Workflow 18: Disbursement to Active Loan

**Purpose:**  
Move approved applications through fund disbursement into active repayment.

**Actors Involved:**  
LSP API Client, System, System Administrator (optional manual trigger).

**Workflow Steps:**
1. Partner submits repayment schedule for the approved application.
2. Partner uploads disbursement-required documents (e.g., KFS, loan agreement).
3. Partner optionally runs disbursement bank check.
4. System identifies application ready for disbursement.
5. System validates documents, schedule, and bank details.
6. System initiates disbursement request on loan account.
7. Disbursement channel processes transfer successfully.
8. Application moves to disbursed, then under repayment.
9. Partner receives disbursement completed notification.

**Final Outcome:**  
Loan is actively serviced; installments are trackable; partner is notified of activation.

---

### Workflow 19: Disbursement Retry After Failure

**Purpose:**  
Recover from transient disbursement failures without losing the approved application.

**Actors Involved:**  
System, Operations staff (via alerts).

**Workflow Steps:**
1. Disbursement attempt fails for a retryable reason.
2. Application moves to disbursement retry status.
3. System re-attempts on subsequent runs up to configured maximum.
4. On success → Workflow 18 completion path.
5. On exhaustion → ops alert (disbursement retry exhausted); application may move to rejected or await admin intervention.

**Final Outcome:**  
Transient failures are retried automatically; persistent failure is escalated for human action.

---

### Workflow 20: Manual Administrator Disbursement Initiation

**Purpose:**
Trigger disbursement on demand when automated processing timing is insufficient.

**Actors Involved:**
System Administrator, System (disbursement service).

**Workflow Steps:**
1. Administrator confirms prerequisites (schedule, documents, bank details) on approved pending disbursal loan.
2. Administrator initiates disbursement from loan detail.
3. System requests disbursement through disbursement channel.
4. Outcome follows success, retry, or failure paths per disbursement workflows.

**Final Outcome:**
Disbursement attempted under admin control; loan progresses or failure is surfaced.

---

### Workflow 21: Disbursement Bank Mismatch Handling

**Purpose:**  
Surface bank detail mismatches before or during disbursement.

**Actors Involved:**  
LSP API Client, System, Operations staff (follow-up).

**Workflow Steps:**
1. Partner runs disbursement bank check or disbursement validation runs.
2. System detects mismatch between expected and provided bank details.
3. Mismatch is logged for operations visibility.
4. Disbursement may be blocked until resolved.

**Final Outcome:**  
Bank issues identified before funds are sent incorrectly.

---

### Workflow 22: Simulate Disbursement Outcome (Non-Production)

**Purpose:**
Test disbursement success and failure paths without real fund movement.

**Actors Involved:**
System Administrator.

**Workflow Steps:**
1. Administrator selects loan in disbursement pipeline in test environment.
2. Administrator submits simulated disbursement outcome.
3. System updates loan account and application per simulated result.

**Final Outcome:**
Test environment reflects disbursement outcomes for QA and demos.

---

### Workflow 23: Disbursement Swimlane

**Purpose:**  
Prerequisites and fund movement from approval to active loan.

**Actors Involved:**  
LSP API Client, System, System Administrator.

**Workflow Steps:**
1. Submit repayment schedule.
2. Complete disbursement documents and bank validation.
3. Automated or manual disbursement executes.
4. Loan becomes disbursed and under repayment on success.

**Final Outcome:**  
Funds disbursed; servicing phase begins.

---

### 4.4 Loan Servicing and Closure

### Workflow 24: Internal Operations Repayment and Loan Closure

**Purpose:**
Record installment payments and close fully repaid loans from the operations console.

**Actors Involved:**
System Administrator or Operations User, System (repayment service).

**Workflow Steps:**
1. User opens loan application detail and schedule tab.
2. User selects record payment and enters full installment amount, date, and reference.
3. System validates amount matches full installment requirement.
4. System allocates payment to next pending installment and marks installment paid.
5. If first payment on a disbursed loan, status advances to under repayment.
6. If last installment is paid, loan closes as fully repaid.
7. Partner receives repayment recorded notification (and fully repaid if closed).

**Final Outcome:**
Payment recorded; schedule updated; loan closed when all obligations are met.

---

### Workflow 25: Partner API Repayment

**Purpose:**  
Allow partners to post repayments through integration mirroring internal ops rules.

**Actors Involved:**  
LSP API Client, System (repayment service).

**Workflow Steps:**
1. Partner submits payment for a disbursed/under-repayment loan.
2. System validates loan status and full installment amount.
3. System records and allocates payment (same rules as Workflow 6).
4. Status and webhooks update accordingly.

**Final Outcome:**  
Servicing records reflect partner-posted payment; loan may close when fully repaid.

---

### Workflow 26: Foreclosure Quote and Execution

**Purpose:**  
Close loans early through foreclosure when business terms allow.

**Actors Involved:**  
System Administrator, LSP API Client (quote only), System (foreclosure service).

**Workflow Steps:**
1. Actor requests foreclosure quote for eligible loan.
2. System calculates payoff amount and quote validity period.
3. Administrator reviews quote and executes foreclosure.
4. System moves loan to foreclosed (terminal).
5. Partner receives foreclosure notification.

**Final Outcome:**  
Loan is early-closed via foreclosure with documented payoff.

---

### Workflow 27: Loan Lifecycle — Full Repayment Closure

**Purpose:**  
Normal successful loan completion when all installments are paid.

**Actors Involved:**  
Operations or partner payment actors, System.

**Workflow Steps:**
1. Payments recorded until all schedule installments are satisfied.
2. System moves loan to closed with fully repaid outcome.
3. Partner receives fully repaid notification.

**Final Outcome:**  
Loan terminally closed in good standing.

---

### Workflow 28: Borrower Bank Details Update

**Purpose:**  
Keep borrower banking information current for disbursement and verification.

**Actors Involved:**  
LSP API Client or System Administrator.

**Workflow Steps:**
1. Actor submits updated bank details for borrower.
2. System validates and persists changes.
3. Change is audited; partner may receive notification.

**Final Outcome:**  
Disbursement and bank check use current bank information.

---

### Workflow 29: Servicing Swimlane

**Purpose:**  
Ongoing repayment, delinquency awareness, and terminal closure.

**Actors Involved:**  
Operations User, System Administrator, LSP API Client, System.

**Workflow Steps:**
1. Record payments (internal or API) against schedule.
2. System tracks installment status and delinquency indicators.
3. Loan closes via full repayment or foreclosure path.

**Final Outcome:**  
Loan reaches terminal closed or foreclosed state.

---

### 4.5 Operations and Portfolio Management

### Workflow 30: Borrower Search and Research

**Purpose:**  
Support operations triage with cross-portfolio borrower visibility.

**Actors Involved:**  
System Administrator or Operations User.

**Workflow Steps:**
1. Staff searches borrower directory by identifiers or name.
2. System returns matches with LSP relationships.
3. Staff reviews profile and linked loan history.

**Final Outcome:**  
Operations has borrower context for decision support (e.g., duplicate loan policy).

---

### Workflow 31: Loan Application Search and Triage

**Purpose:**  
Find applications across the portfolio for operations work.

**Actors Involved:**  
Internal users (System Administrator, Operations User).

**Workflow Steps:**
1. User opens loan applications list.
2. User applies filters (status, LSP, dates, etc.).
3. User opens detail for documents, schedule, payments, or escalation.

**Final Outcome:**  
Target loans identified and opened for next workflow step.

---

### Workflow 32: Portfolio Dashboard Review

**Purpose:**  
Executive and admin snapshot of portfolio metrics.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. Administrator opens home/portfolio dashboard.
2. System displays aggregated portfolio overview.
3. Administrator uses metrics to prioritize operational focus.

**Final Outcome:**  
Leadership has current portfolio visibility.

---

### Workflow 33: Operations KYC Document Review

**Purpose:**  
Allow Bhawana staff to retrieve borrower documents for verification and support.

**Actors Involved:**  
System Administrator or Operations User.

**Workflow Steps:**
1. Staff opens loan application from operations queue.
2. Staff selects KYC document to view or download.
3. System authorizes and delivers document.
4. Access is logged for compliance.

**Final Outcome:**  
Staff completes document review with traceable access.

---

### Workflow 34: Operations Escalation to Administrator

**Purpose:**  
Route complex or stuck loans from operations to administrators.

**Actors Involved:**  
Operations User, System Administrator.

**Workflow Steps:**
1. Operations user identifies loan requiring admin intervention.
2. User escalates from loan detail with context.
3. System creates ops user escalation alert.
4. Administrator acknowledges alert and takes action (status transition, override, disbursement, etc.).

**Final Outcome:**  
Escalation is visible and acted upon by administrators.

---

### Workflow 35: Manual Status Transition (Administrator)

**Purpose:**  
Move loans through governed lifecycle states when automated paths are insufficient.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. Administrator opens loan detail action bar.
2. Administrator selects permitted target status and required justification.
3. System validates transition against state machine.
4. Application and loan account update; audit and webhooks fire as applicable.

**Final Outcome:**  
Loan is in administratively approved next state.

---

### Workflow 36: Manual Status Override (Administrator)

**Purpose:**  
Resolve exceptional cases outside normal transition rules.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. Administrator selects manual override with target status and reason code.
2. System applies override.
3. Change is fully audited.

**Final Outcome:**  
Exceptional status correction applied with audit trail.

---

### 4.6 Reporting

### Workflow 37: Portfolio MIS Preview and Sync Download

**Purpose:**  
Obtain MIS data immediately for smaller or urgent reporting needs.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. Administrator sets report parameters on reports page.
2. Administrator chooses preview or synchronous download.
3. System generates and returns report data or file in the same session.

**Final Outcome:**  
Administrator has MIS output without waiting for background job completion.

---

### Workflow 38: Async MIS Report Generation

**Purpose:**  
Produce large portfolio MIS exports for management reporting without blocking users.

**Actors Involved:**  
System Administrator, System, optional email notification.

**Workflow Steps:**
1. Administrator selects LSP filter and disbursal date range on reports page.
2. Administrator submits async report request.
3. System creates pending report request.
4. System claims request and moves to processing.
5. System generates CSV and stores file.
6. Request status becomes completed; optional email notifies administrator.
7. Administrator downloads report from reports page.

**Final Outcome:**  
MIS CSV is available for download and management analysis.

---

### Workflow 39: Platform Services — Reporting Swimlane

**Purpose:**  
Management reporting parallel to core loan lifecycle.

**Actors Involved:**  
System Administrator, System.

**Workflow Steps:**
1. Request MIS preview, sync download, or async report.
2. System generates portfolio CSV for selected filters.
3. Administrator downloads and analyzes output.

**Final Outcome:**  
MIS reporting supports management and regulatory needs.

---

### 4.7 Alerting and Escalation

### Workflow 40: Operations Alert Acknowledgment and Triage

**Purpose:**  
Manage operational alerts from detection to resolution.

**Actors Involved:**  
System Administrator, Operations User, System (alert scheduler).

**Workflow Steps:**
1. Scheduled rules or event hooks create ops alerts (stale intake, stuck disbursement, delinquency bucket change, webhook dead letter, auto-reject spike, user escalation, etc.).
2. Staff reviews alerts on alerts console.
3. Staff acknowledges alerts under review.
4. Staff takes corrective action on underlying loan or integration issue.

**Final Outcome:**  
Alerts are tracked from creation through acknowledgment and resolution.

---

### Workflow 41: Scheduled Alert Rule Evaluation

**Purpose:**  
Continuously monitor portfolio and platform health against configured thresholds.

**Actors Involved:**  
System.

**Workflow Steps:**
1. System runs on schedule.
2. System evaluates alert rules against current data.
3. New or updated alerts are created for staff per Workflow 30.

**Final Outcome:**  
Proactive visibility into operational risk.

---

### Workflow 42: Platform Services — Alerting Swimlane

**Purpose:**  
Operational monitoring parallel to core loan lifecycle.

**Actors Involved:**  
System, Operations User, System Administrator.

**Workflow Steps:**
1. Rules and events generate alerts.
2. Staff triages and acknowledges.
3. Corrective workflows address underlying loans or integrations.

**Final Outcome:**  
Operational issues surfaced and managed proactively.

---

### 4.8 Webhooks and Partner Sync

### Workflow 43: Webhook Event Delivery

**Purpose:**  
Notify partners of loan and platform events in near real time.

**Actors Involved:**  
System, External LSP webhook consumer.

**Workflow Steps:**
1. Business event occurs (loan created, status changed, disbursement, repayment, etc.).
2. System enqueues event in webhook outbox.
3. System delivers signed payload to partner HTTPS endpoint.
4. On success → delivered.
5. On transient failure → retryable failure and automatic retry.
6. On persistent failure → permanent failure for admin redrive.

**Final Outcome:**  
Partner systems stay synchronized with LMS state, or failures are classified for recovery.

---

### Workflow 44: Manual Webhook Redrive

**Purpose:**  
Recover permanently failed webhook deliveries after partner endpoint repair.

**Actors Involved:**  
System Administrator, System.

**Workflow Steps:**
1. Administrator identifies permanent failure in webhook admin view.
2. Administrator initiates redrive (within maximum redrive attempts).
3. System re-queues event and records redrive audit.
4. System attempts delivery again.

**Final Outcome:**  
Previously failed event is delivered or failure reason is updated.

---

### Workflow 45: Platform Services — Webhooks Swimlane

**Purpose:**  
Partner notification layer across origination, disbursement, and servicing.

**Actors Involved:**  
System, External LSP webhook consumer, System Administrator.

**Workflow Steps:**
1. Domain events enqueue notifications throughout loan lifecycle.
2. System delivers to partner endpoints with retry.
3. Administrator redrives permanent failures when needed.

**Final Outcome:**  
Partner systems remain synchronized with LMS events.

---

### 4.9 Audit and Compliance

### Workflow 46: Audit Explorer Investigation

**Purpose:**  
Forensic review across application, intake, document access, product, user, API client, disbursement, and report domains.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. Administrator opens audit explorer.
2. Administrator filters by domain, time, entity, or correlation identifier.
3. System presents unified timeline across audit streams.
4. Administrator traces related events for incident or compliance review.

**Final Outcome:**  
Cross-domain investigation completed with linked audit history.

---

### Workflow 47: Authentication Audit Review

**Purpose:**  
Review sign-in patterns and authentication events for security.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. Administrator queries authentication audit events.
2. System returns login successes, failures, and related activity.
3. **Needs Clarification:** Dedicated UI availability for this workflow is unclear in source documentation.

**Final Outcome:**  
Authentication activity reviewed for security or support purposes.

---

### Workflow 48: Platform Services — Audit Swimlane

**Purpose:**  
Compliance and investigation support across all domains.

**Actors Involved:**  
System Administrator.

**Workflow Steps:**
1. System records auditable actions during onboarding, origination, disbursement, servicing, and reporting.
2. Administrator searches audit explorer (and auth audit where needed).
3. Investigator traces activity using filters and correlation.

**Final Outcome:**  
Auditability supports compliance, security, and incident response.

---

### 4.10 LSP Self-Service

### Workflow 49: LSP My Loans Self-Service (Read)

**Purpose:**  
Partner read-only visibility into tenant loan book.

**Actors Involved:**  
LSP UI User (Read or Write).

**Workflow Steps:**
1. Partner user signs in and opens My Loans.
2. User views list and detail of own-tenant applications only.
3. Write role may continue to Workflow 13 actions.

**Final Outcome:**  
Partner staff monitor pipeline without cross-tenant data exposure.

---

### Workflow 50: LSP Product Catalog Retrieval

**Purpose:**  
Partners confirm assignable products before origination.

**Actors Involved:**  
LSP API Client or LSP UI User.

**Workflow Steps:**
1. Partner requests product catalog for tenant.
2. System returns active mapped products with terms and bounds.

**Final Outcome:**  
Partner originates only against valid products.

--
