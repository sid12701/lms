# UI Pages

This document describes the current UI surface of the Bhawana LMS React frontend. It covers the active routes, the data presented on each page, shared layout conventions, role access, interaction states, and the visual theme used across the app.

## Frontend Stack

- App type: React single page app under `frontend/`.
- Build tooling: Vite, TypeScript, React 19, React Router, TanStack Query, Tailwind CSS v4.
- UI approach: shadcn-style local primitives in `frontend/src/components/ui`, plus custom app primitives in `frontend/src/components/app`.
- Icons: `lucide-react`.
- Animation: `framer-motion` for route transitions and the blue loading state.
- API base: `VITE_API_BASE_URL`, defaulting to `http://localhost:8080`.
- Primary product identity: "Bhawana Capital" and "Sovereign Ledger".

## Route Inventory

| Route | Page | Access | Main file |
| --- | --- | --- | --- |
| `/login` | Login | Public, redirects authenticated users | `frontend/src/features/auth/login-page.tsx` |
| `/change-password` | Mandatory password update | Authenticated users with `mustChangePassword` | `frontend/src/features/auth/change-password-page.tsx` |
| `/` | Landing redirect | Resolves to `/login`, `/change-password`, `/my-loans`, or `/home` | `frontend/src/router.tsx` |
| `/dashboard` | Legacy redirect | Protected, redirects to `/home` | `frontend/src/router.tsx` |
| `/home` | Home / dashboard | Protected | `frontend/src/features/home/home-page.tsx` |
| `/my-loans` | LSP loans workspace | LSP UI users only | `frontend/src/features/lsp-loans/lsp-loans-page.tsx` |
| `/lsps` | LSP tenant registry | Internal users only; nav shown to `SYSTEM_ADMIN` | `frontend/src/features/admin/lsp-admin-page.tsx` |
| `/api-clients` | API client credentials | Internal users only; direct route | `frontend/src/features/api-clients/api-clients-page.tsx` |
| `/users` | User administration | Internal users only; nav shown to `SYSTEM_ADMIN` | `frontend/src/features/users/users-page.tsx` |
| `/products` | Loan product configuration | Internal users only; nav shown to `SYSTEM_ADMIN` and `PRODUCT_ADMIN` | `frontend/src/features/products/product-configuration-page.tsx` |
| `/loan-applications` | Internal loan ledger | Internal users only | `frontend/src/features/loan-applications/loan-applications-page.tsx` |
| `/loan-applications/:id` | Loan application detail | Internal users only | `frontend/src/features/loan-applications/loan-application-detail-page.tsx` |
| `/borrowers/:id` | Borrower detail | Internal users only | `frontend/src/features/borrowers/borrower-detail-page.tsx` |
| `/alerts` | Operational alerts | Internal users only; gated to `SYSTEM_ADMIN` and `OPS_USER` | `frontend/src/features/alerts/alerts-page.tsx` |
| `/reports` | Portfolio MIS | Internal users only; gated to `SYSTEM_ADMIN` | `frontend/src/features/reports/reports-page.tsx` |

## Role And Navigation Model

- `LSP_UI_READ` and `LSP_UI_WRITE` users are treated as LSP UI users. Their default landing page is `/my-loans`.
- All other authenticated users default to `/home`.
- Users who must update a temporary password are forced to `/change-password` before using protected routes.
- Internal-only routes reject LSP UI users and redirect them to `/my-loans`.
- LSP-only routes reject internal users and redirect them to `/loan-applications`.
- Reports are available only to `SYSTEM_ADMIN`.
- Alerts are available to `SYSTEM_ADMIN` and `OPS_USER`.
- LSP management and user management are shown only to `SYSTEM_ADMIN`.
- Product management is shown to `SYSTEM_ADMIN` and `PRODUCT_ADMIN`.

The sidebar navigation is built from role-aware items:

- LSP users: Home, My Loans.
- Internal users: Home, Loan applications, optional LSPs, optional Loan products, optional Users, optional Alerts.
- Reports live under a collapsible Reports group with MIS as the current child item.

## Shared App Shell

The protected UI uses `AppShell`.

- Layout: two-column desktop grid with a 260px sidebar and content column.
- Mobile/tablet behavior: sidebar stacks above the content because the desktop grid starts at the `lg` breakpoint.
- Main content: padded content region with a max-width of 1100px and vertical page spacing.
- Header:
  - Search input with placeholder `Search ledger, loans, or clients...`.
  - JWT session badge.
  - Active profile badge.
  - Notification and help icon buttons.
  - User context block showing either `LSP Workspace` or `Institutional Admin`.
  - Avatar initials generated from username.
- Sidebar:
  - Brand block with `Bhawana Capital` and `Sovereign Ledger`.
  - Role-specific positioning copy.
  - Active nav links shown as white cards with navy text and shadow.
  - Actor context footer showing username, application, primary role, scope, and sign-out button.
- Route transitions:
  - `AnimatePresence` and `motion.section` fade/translate pages.
  - Reduced-motion preferences are respected.

## Shared UI Components

- `PageHeader`: eyebrow, H1, description, and optional action slot.
- `PageSection`: card-like section with optional eyebrow/title/description/actions and content area.
- `MetricCard`: KPI tile with label, value, optional detail, optional accent, and default/danger/success tone.
- `ContentState`: loading, empty, or error state wrapper.
- `BlueLoader`: animated blue progress bar with title/description; compact and full-height modes.
- `AdminSurface`: white elevated surface used by admin pages.
- `AdminBadge`: admin badge variants for default, success, warning, and destructive states.
- `AdminButton`: admin-specific button variants for primary, secondary, ghost, and destructive.
- `Button`: generic app button with default/primary, secondary, outline, ghost, destructive and sm/default/lg/icon sizes.
- `Badge`: generic status badge with default, success, warning, and destructive variants.
- `Card`: local card primitive with header, title, description, content, and footer parts.
- `Input`, `DatePicker`, `Select`, `Tabs`, `Table`, `Textarea`, `Checkbox`, and related primitives support forms, filtering, tables, and report controls.

## Visual Theme

The primary UI theme is a light institutional operations console with navy, white, muted gray-blue surfaces, and warm gold accents.

### Core Tokens

| Token | Value | Use |
| --- | --- | --- |
| Navy | `#000666` | Primary brand color, headings, main actions, active states |
| Navy light | `#0a1a7a` | Button hover, gradients |
| Navy dark | `#000444` | Dark sidebar gradient support |
| Navy deep | `#000333` | Deepest navy token |
| Background | `#f8f9fc` | App background |
| Surface | `rgba(255, 255, 255, 0.88)` | Cards and elevated panels |
| Surface alt | `#eef1f8` | Inputs, muted panels, admin empty states |
| Surface raised | `rgba(255, 255, 255, 0.96)` | Auth panel and raised blocks |
| Foreground | `#0f1729` | Main text |
| Muted | `#5e6680` | Secondary copy and labels |
| Muted light | `#8a92a8` | Placeholder and tertiary text |
| Accent | `#b48e4b` | Warm highlight and warning-adjacent accent |
| Accent warm | `#c9a05a` | Auth hero accent text |
| Success | `#167a54` | Positive badges and success states |
| Warning | `#a67c1a` | Attention badges |
| Danger | `#b23a48` | Destructive and error states |

### Supporting Colors

- Primary gradient: `#000666` to `#1a237e`.
- Loader accent: `#2f62ff`.
- Dashboard chart/support colors: `#343c9b`, `#69a9f5`, `#7dd37e`, `#c9cbd8`, `#bf1d22`.
- Admin list backgrounds: `#f8f9fa`, `#f3f4f5`, `#eef1f8`.
- Selected admin/list items often use an inset left navy rule with navy-tinted shadow.

### Typography

- Font family: system UI stack.
- Headings use the display/font-heading utility.
- Page titles are typically 3xl to 4xl, bold, navy, and tightly tracked.
- Section labels and table heads use small uppercase text with wide tracking.
- Body copy uses muted gray-blue text and compact operational wording.

### Shape, Depth, And Motion

- Radius tokens: 8px, 12px, 16px, 20px.
- Generic cards use large rounded corners and navy-tinted shadows.
- Admin surfaces use 8px rounded corners and stronger elevated shadows.
- Buttons use 8px to 16px radius depending on size.
- Shadows are subtle and navy-toned rather than black-heavy.
- Route transitions fade and slide; loaders use a moving blue sweep and pulsing dots.

## Page Breakdown

### Login - `/login`

Purpose: public entry into the internal operations console.

Presented data and UI:

- Username field, defaulted in local UI to `ops.admin`.
- Password field, defaulted in local UI to `ChangeMe123!`.
- Submit button labelled `Enter console`.
- Error area for login failure or forced password-change requirement.
- Helper copy explaining JWT-backed internal access.
- Hero panel with Bhawana Capital branding and three cards:
  - JWT bootstrap auth.
  - Four ops lanes.
  - All-LSP visibility.

Actions and data calls:

- Calls password login through the auth context, backed by `/api/v1/auth/login`.
- On success, routes to `/change-password` when required or `/dashboard` otherwise.
- `/dashboard` then redirects to `/home`.

Visual notes:

- Two-column auth layout on desktop.
- Left white raised form panel, right navy gradient hero.
- Mobile hides the hero and shows only the form panel.

### Change Password - `/change-password`

Purpose: mandatory password handoff for temporary credentials.

Presented data and UI:

- New password field.
- Confirm password field.
- Current username in the page description when available.
- Error area for missing values, mismatch, or backend failure.
- Submit button labelled `Update password`.
- Hero panel with secure handoff messaging and cards for locked routes, new password, and resume.

Actions and data calls:

- Calls `/api/v1/auth/password` through `completePasswordChange`.
- On success, refreshes session state and returns to `/dashboard`.

Visual notes:

- Same auth layout as login.
- Uses the navy hero gradient and elevated form card.

### Home - `/home`

Purpose: landing workspace after login. The content changes by role.

Admin/internal version:

- Header title: `Dashboard Summary`.
- CTA: `New Loan Application`, linking to `/loan-applications`.
- Loads live dashboard data from:
  - `/api/v1/internal/home/overview`
  - `/api/v1/internal/reports/portfolio-mis/summary`

Presented data:

- Total Disbursed Amount.
- Total Active Amount.
- Total Overdue Amount.
- Loan Status Distribution chart using static month bars with live summary values below.
- Active Retention.
- Average Loan Size.
- Repayment Rate.
- Lead Velocity, currently shown as static `+15%`.
- Pipeline Health:
  - Active accounts.
  - Total loans.
  - LSPs reporting.
  - Priority queue.
  - 90+ DPD loans.
- Portfolio Risk Index from `portfolioAtRiskPct`.
- Critical Disbursements table:
  - Borrower/customer name.
  - LSP code.
  - External loan ID.
  - Principal amount.
  - Interest rate.
  - Loan status.
  - Inspect loan action linking to loan detail.

LSP/non-admin workspace version:

- Header title: `Operational workspace`.
- Shows role-available `HomeLinkCard` entries:
  - LSP users see My Loans.
  - Internal users can see Loan applications, LSPs, Reports, Loan products, and Users depending on role capabilities.

States:

- Session loading state when user data is not ready.
- Loading state for dashboard queries.
- Error state when overview or MIS summary fails.

### My Loans - `/my-loans`

Purpose: tenant-scoped LSP loan visibility and limited self-service.

Access:

- LSP UI users only.
- `LSP_UI_WRITE` enables invalidation controls.

Data sources:

- Loan list: `/api/v1/lsp/loan-applications`.
- Loan detail: `/api/v1/lsp/loan-applications/{applicationId}`.
- Invalidation reasons: `/api/v1/lsp/loan-applications/invalid-reasons`.
- Invalidate loan: `/api/v1/lsp/loan-applications/{applicationId}/invalid` with an idempotency key.

Presented data:

- Tenant title from `user.lspName`, `user.scope`, or `My loans`.
- Search field for external loan ID, borrower, PAN, or mobile.
- CSV export for the currently loaded loans.
- Loan count and LSP badge.
- Loan list rows:
  - Borrower.
  - External loan ID.
  - Status badge.
  - Requested amount.
  - Created timestamp.
- Detail panel for selected loan:
  - Borrower name and mobile.
  - Status and source channel.
  - Requested amount.
  - Tenure.
  - Product.
  - Loan account number and account status.
  - Delinquency bucket, max DPD, overdue amount.
  - Repayment installment count and EMI amount.
  - Latest activity summary, actor, and timestamp.
  - Invalidation metadata when already invalidated.

Actions:

- Search updates the backend query.
- Export CSV downloads a local `lsp-loans-report.csv`.
- Select a loan row to load details.
- `LSP_UI_WRITE` users can mark a selected loan invalid by choosing a reason and entering required detail text.

States:

- BlueLoader for list and detail loading.
- Empty state when no loans match the filter.
- Error state for list/detail/invalidation failures.

### LSP Tenant Registry - `/lsps`

Purpose: manage tenant registry, inspect portfolio summaries, review sanctioned users, and configure webhooks.

Access:

- Internal route.
- Sidebar item appears only to `SYSTEM_ADMIN`.

Data sources:

- Metadata: `/api/v1/internal/admin/metadata`.
- LSP list: `/api/v1/internal/admin/lsps`.
- LSP options invalidation: `/api/v1/internal/admin/lsp-options`.
- LSP detail: `/api/v1/internal/admin/lsps/{lspId}`.
- Create LSP: `POST /api/v1/internal/admin/lsps`.
- Status change (disable / reactivate): `PUT /api/v1/internal/admin/lsps/{lspId}/status` with `reason` + `note` (see ADR `docs/adr/0002-lsp-disable-kill-chain.md`, issue **#63**).
- Status audit trail: `GET /api/v1/internal/admin/lsps/{lspId}/audit-events`.
- Webhook save: `PUT /api/v1/internal/admin/lsps/{lspId}/webhook-subscription`.

**`frontend-2` (`/lsps`)** — table with **Details**, **Status**, **Audit**, **Webhook**. Use **Status** (not a generic edit form) to disable; audit dialog shows `LSP_DISABLED` / `LSP_REACTIVATED` rows.

Presented data:

- Registry panel:
  - Tenant count.
  - Status count.
  - Tenant cards with name, code, status, latest disbursal date, loan count, disbursed count, and total disbursed amount.
- Selected LSP panel:
  - Code and status.
  - User count.
  - Webhook on/off badge.
  - Portfolio metrics: loans captured, approved, disbursed, amount disbursed.
  - Sanctioned users list with username, email, roles, and status.
  - Webhook form:
    - Delivery enabled/disabled.
    - Endpoint URL.
    - Signing secret.
    - Event type checkboxes.
- Create LSP panel:
  - Tenant code.
  - Tenant name.
  - Status.

Actions:

- Select an LSP from the registry.
- Create tenant.
- Toggle webhook event types.
- Save webhook subscription.

States:

- Loading states for registry and detail.
- Permission-denied states for 401/403.
- Local form validation for webhook endpoint, signing secret, and event type selection.
- Success message after saving webhook subscription.

### API Clients - `/api-clients`

Purpose: issue and review machine credentials for external LSP integrations.

Access:

- Internal route.
- Not currently included in the sidebar navigation, but the route is active.

Data sources:

- Metadata: `/api/v1/internal/admin/metadata`.
- LSP options: `/api/v1/internal/admin/lsp-options`.
- API clients: `/api/v1/internal/admin/api-clients`.
- Create API client: `POST /api/v1/internal/admin/api-clients`.

Presented data:

- API client list:
  - Client count.
  - LSP count.
  - Client name.
  - Client ID.
  - Status.
  - LSP name.
  - Last-used indicator.
- External LSP API contract panel:
  - Token endpoint: `POST /api/v1/auth/token`.
  - LSP application endpoints shown as reference text.
  - JSON credential sample.
- Secret-issued-once panel after creating a client:
  - Client name.
  - Client ID.
  - Client secret.
  - Short secret preview.
- Create API client form:
  - Client name.
  - LSP selector.
  - Status selector.

Actions:

- Create API client.
- Acknowledge and hide the once-visible secret.

States:

- BlueLoader while metadata, LSPs, or clients load.
- Empty state when no API clients exist.
- Error state from query or create failures.

### Users - `/users`

Purpose: manage internal and tenant-scoped console users.

Access:

- Internal route.
- Sidebar item appears only to `SYSTEM_ADMIN`.

Data sources:

- Metadata: `/api/v1/internal/admin/metadata`.
- LSP options: `/api/v1/internal/admin/lsp-options`.
- Users: `/api/v1/internal/admin/users`.
- Create user: `POST /api/v1/internal/admin/users`.
- Reset password: `POST /api/v1/internal/admin/users/{userId}/reset-password`.

Presented data:

- User registry:
  - User count.
  - Role-code count.
  - Rows with username, email, status, primary role, tenant scope, and reset password action.
- Temporary password issued panel:
  - Username.
  - Temporary password, shown once.
  - Acknowledge and hide action.
- Create user form:
  - Username.
  - Email.
  - Temporary password.
  - Role code.
  - Status.
  - Tenant scope.

Business logic shown in UI:

- LSP scope is required for `LSP_UI_READ` and `LSP_UI_WRITE`.
- LSP scope is disabled/not required for internal roles.

States:

- BlueLoader while registry data loads.
- Permission-denied state for missing system-admin access.
- Empty state when no users exist.
- Local errors for creation and reset failure.

### Loan Product Configuration - `/products`

Purpose: manage loan products, pricing, tenure rules, and mapped LSP access.

Access:

- Internal route.
- Sidebar item appears to `SYSTEM_ADMIN` and `PRODUCT_ADMIN`.

Data sources:

- Metadata: `/api/v1/internal/admin/metadata`.
- Products: `/api/v1/internal/admin/products`.
- LSP options: `/api/v1/internal/admin/lsp-options`.
- Product-LSP mappings: `/api/v1/internal/admin/product-lsp-mappings`.
- Create product: `POST /api/v1/internal/admin/products`.
- Update product: `PUT /api/v1/internal/admin/products/{productId}`.
- Save mappings: `PUT /api/v1/internal/admin/product-lsp-mappings/{productId}`.

Presented data:

- Page header:
  - `Loan Product Configuration`.
  - Copy explaining product configuration and mapped LSP access.
- Product list panel:
  - Product count.
  - Product cards with product name, code, status, maximum principal, and mapped LSP count.
  - Create loan product button.
- Product detail panel:
  - Status badge.
  - Product name and code.
  - Mapped LSP count.
  - Edit product action.
  - Principal range.
  - Interest rate.
  - Processing fee.
  - Tenure range.
  - Mapped LSP cards with name, UUID preview, code, and status.
- Create/edit form:
  - Product name.
  - Product code.
  - Optional first LSP mapping on create.
  - Status.
  - Mapped LSP access editor on edit.
  - Minimum and maximum amount.
  - Interest percent.
  - Fee percent.
  - Minimum and maximum tenure.

Actions:

- Select product.
- Open create form.
- Edit product.
- Add/remove mapped LSPs during edit.
- Create product.
- Save product changes.
- Cancel editor.

States:

- BlueLoader while products, metadata, LSPs, and mappings load.
- Permission-denied state for missing admin/product access.
- Empty states for no products or no LSP mappings.
- Error state for save failures.

### Loan Applications - `/loan-applications`

Purpose: internal operational queue for loan applications and borrower workflows.

Access:

- Internal route only.

Data sources:

- LSP options: `/api/v1/internal/admin/lsp-options`.
- Products: `/api/v1/internal/admin/products`.
- Loan applications: `/api/v1/internal/ops/loan-applications`.

Presented data:

- Page header:
  - Eyebrow: Loan ledger.
  - Title: Loan applications.
  - Description: live operational queue.
  - Refresh action.
- KPI cards:
  - Queue size.
  - Portfolio value across disbursed and repayment-stage applications.
  - Awaiting approval count.
  - Disbursed count.
- Filters:
  - Status.
  - LSP.
  - Product.
  - Search by borrower, PAN, or external ID.
  - Clear all.
- Applications table:
  - Bhawana Loan ID.
  - External ID.
  - Borrower with avatar initials and borrower-detail link.
  - LSP code.
  - Product name/code.
  - Requested amount.
  - Status badge.
  - Tenure.
  - View details action.

Actions:

- Refresh increments a query refresh counter.
- Filter changes update backend query parameters.
- Clear all resets filters.
- Loan ID and View details navigate to `/loan-applications/:id`.
- Borrower name navigates to `/borrowers/:id` when borrower ID is present.

States:

- Loading state while options/products/applications load.
- Error state for query failures.
- Empty state when filters return no applications.

### Loan Application Detail - `/loan-applications/:id`

Purpose: inspect one internal loan application with servicing, borrower, document, and workflow details.

Access:

- Internal route only.

Data sources:

- Loan detail: `/api/v1/internal/ops/loan-applications/{applicationId}`.
- Repayment schedule: `/api/v1/internal/ops/loan-applications/{applicationId}/repayment-schedule`.
- KYC documents: `/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents`.
- Status transition: `POST /api/v1/internal/ops/loan-applications/{applicationId}/status-transitions`.
- Disbursal initiation: `POST /api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests`.
- Download all documents: `/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/download-all`.

Presented data:

- Back to Loans button.
- Page header:
  - Loan application eyebrow.
  - Full loan ID.
  - Borrower, product, and requested amount.
  - Current loan status badge.
- Action bar:
  - Last modified or created timestamp.
  - Actor username when available.
  - Workflow actions based on status and role.
- Loan overview:
  - Principal.
  - Tenure.
  - Product.
  - EMI amount, first due, final due when account schedule exists.
  - Max DPD, DPD bucket, overdue amount when delinquency exists.
  - Loan account status badge.
- Repayment schedule:
  - EMI number.
  - Due date.
  - Principal due.
  - Interest due.
  - EMI amount.
  - Days past due.
  - Installment status.
- Borrower profile:
  - Avatar initials.
  - Borrower link.
  - KYC badge.
  - PAN.
  - Mobile.
  - Email.
  - Location.
  - Employment.
  - Monthly income.
  - Assigned-to username.
- Verification documents:
  - Document display name.
  - Uploaded timestamp.
  - Status badge.
  - Download All as ZIP when LMS-managed files exist.
- Application info:
  - LSP name.
  - LSP code.
  - Source channel.
  - External loan ID.
  - Created timestamp.
  - Invalid reason and invalidated metadata when present.
  - Delinquency summary when present.

Role-based actions:

- `SYSTEM_ADMIN` and `OPS_USER` can submit `INITIALIZED` loans for review.
- `SYSTEM_ADMIN` can approve or reject `AWAITING_APPROVAL` loans.
- `SYSTEM_ADMIN` can initiate disbursal when status is `APPROVED_PENDING_DISBURSAL` and account status is `PENDING_DISBURSEMENT`.

States:

- Loading state while detail data loads.
- Error state when loan is missing or fetch fails.
- Empty repayment schedule state.
- Document availability state.
- Action error state for transition, disbursal, or document ZIP failure.

### Borrower Detail - `/borrowers/:id`

Purpose: show a consolidated borrower profile and loan history across LSPs.

Access:

- Internal route only.

Data source:

- Borrower detail: `/api/v1/internal/admin/borrowers/{borrowerId}`.

Presented data:

- Back button.
- Page header:
  - Borrower full name.
  - PAN.
  - Active and closed loan counts.
  - Active borrower/no active loans badge.
  - KYC on file badge.
- Profile section:
  - Avatar initials.
  - Full name.
  - PAN.
  - Mobile.
  - Email.
  - Date of birth.
  - Gender.
  - Marital status.
  - Father's name.
  - Spouse name.
  - Masked Aadhar.
- Address section:
  - Residential address.
  - City/state.
  - Zip.
- Loans across LSPs:
  - LSP name/code.
  - Product code.
  - Account number or application link.
  - Principal.
  - Tenure.
  - Account status badge.
  - Created timestamp.
- Employment side section:
  - Type.
  - Organization.
  - Employee ID.
  - Employment location.
  - Monthly income.
  - Annual income.
- Banking side section:
  - Bank.
  - Account holder.
  - Masked account number.
  - IFSC.
- Reference side section:
  - Reference name.
  - Reference contact.
- Visibility side section:
  - Count of LSPs granted borrower visibility.

States:

- Loading state while borrower data loads.
- Error state when borrower is missing or fetch fails.
- Empty loan-history state when no loans exist.

### Operational Alerts - `/alerts`

Purpose: review and acknowledge backend operational alerts.

Access:

- Internal route.
- Gated to `SYSTEM_ADMIN` and `OPS_USER`.

Data sources:

- Alerts list: `/api/v1/internal/alerts`.
- Acknowledge alert: `POST /api/v1/internal/alerts/{alertId}/acknowledge`.

Presented data:

- Page header with title `Operational alerts`.
- KPI cards:
  - Active Alerts.
  - Critical Priority.
  - Acknowledged.
  - Latest Ingest.
- Filter bar:
  - Tabs: All alerts, Critical, High, Awaiting acknowledgment.
  - Sort select: newest first or severity high-to-low.
- Alerts table:
  - Alert title.
  - Severity badge.
  - Alert type.
  - Subject type.
  - Subject ID or correlation ID.
  - Message.
  - Status badge.
  - Review/Hide action.
- Expanded alert row:
  - Alert summary.
  - Subject card.
  - Severity card.
  - Created timestamp card.
  - Context preview parsed from JSON when possible.
  - Acknowledgment state.
  - Correlation ID.
  - Acknowledge alert button.

Actions:

- Filter and sort in memory.
- Expand/collapse alert review row.
- Acknowledge an alert and update query cache.

States:

- Loading state while alerts load.
- Error state for list or acknowledge failures.
- Empty state for filters with no matching alerts.

### Portfolio MIS - `/reports`

Purpose: internal MIS reporting with live summary, preview, CSV export, background generation, and request history.

Access:

- Internal route.
- Gated to `SYSTEM_ADMIN`.

Data sources:

- LSP options: `/api/v1/internal/admin/lsp-options`.
- Summary: `/api/v1/internal/reports/portfolio-mis/summary`.
- Preview: `/api/v1/internal/reports/portfolio-mis/preview`.
- Immediate CSV download: `/api/v1/internal/reports/portfolio-mis`.
- Queue background report: `POST /api/v1/internal/reports/portfolio-mis/requests`.
- Request history: `/api/v1/internal/reports/requests`.
- Generated report download: `/api/v1/internal/reports/requests/{requestId}/download`.

Presented data:

- Page header:
  - Eyebrow: Internal reporting.
  - Title: Portfolio MIS.
- Summary KPI cards:
  - Total Disbursed (MTD).
  - Active Loan Count.
  - Weighted Avg. Yield.
  - Portfolio at Risk (PAR 30).
- Portfolio filters:
  - LSP selector.
  - Disbursal date from.
  - Disbursal date to.
  - Apply Filters button.
  - Error/success banner.
- Loan transaction ledger preview:
  - Result range and total count.
  - Export to Excel action.
  - Queue export action.
  - Paginated wide table.
- Preview table columns:
  - Loan ID.
  - Borrower.
  - LSP.
  - Product.
  - Amount.
  - Status.
  - Disbursal Date.
  - DPD.
  - Year.
  - LSP Loan ID.
  - Processing Fee.
  - Disbursal Amount.
  - Interest %.
  - Tenure.
  - EMI Amount.
  - Overdue Amount.
  - Closure Date.
  - Foreclosure Date.
  - Foreclosed Amount.
  - PAN.
  - Aadhar.
  - Gender.
  - State.
  - Zip.
  - IFSC.
  - Bank Account.
  - Profession.
  - Income.
  - Dynamic EMI columns based on max installment count in the page.
- Background export panel:
  - Notification email.
  - Generate portfolio MIS button.
- Generated request history:
  - Request count.
  - Refresh button.
  - Report type.
  - Request metadata.
  - LSP/date range.
  - Status badge.
  - Completion time or worker fallback.
  - Download action for completed reports.

Actions:

- Apply filters validates the date range and updates applied report filters.
- Preview pagination uses a fixed page size of 50.
- Export to Excel downloads the current CSV response.
- Queue export creates a report request with optional email.
- Pending/processing requests are polled every 5 seconds.
- Download completed request files.

States:

- Loading and previewing states.
- Empty state when no loan accounts match.
- Error state for date validation, query failure, download failure, or queue failure.
- Success state after a report request is queued.

## Data Model Summary By Area

### Auth/session

- Access token, token type, expiry, password-change requirement.
- User context: username, roles, primary role, scope, LSP ID/name, application, active profiles, correlation ID.

### Tenant/admin

- LSP: ID, code, name, status, webhook subscription, user count, portfolio summary.
- Webhook subscription: enabled, endpoint URL, signing secret, event types.
- User: ID, username, email, status, LSP scope, LSP name, roles.
- API client: ID, client ID, name, LSP scope, status, created time, last-used time, one-time secret on create.
- Admin metadata: role codes, statuses for LSPs/users/API clients/products.

### Product

- Product ID, code, name, principal min/max, interest rate, processing fee rate, tenure min/max, status.
- Product-to-LSP mapping: product ID and mapped LSP IDs.

### Loans and borrowers

- Loan application: borrower identity/contact, LSP, product, external loan ID, source channel, amount, tenure, status, assignee, created time.
- Loan detail: updated time, loan account summary, last activity, invalidation metadata.
- Loan account: account number, account status, principal, tenure, approval/creation/closure metadata, delinquency, schedule summary.
- Repayment installment: installment number, due date, principal, interest, EMI, paid/outstanding amounts, DPD, delinquency bucket, status.
- Documents: type, display name, required flag, status, notes/reasons, file metadata, storage key, uploaded/updated metadata.
- Borrower profile: identity, contact, address, employment, income, banking, references, visible LSP IDs, related loans.

### Reports

- MIS summary: total disbursed, active loan count, weighted average interest rate, portfolio at risk, total loan count.
- MIS preview row: LSP, application, borrower, product, account, amounts, dates, DPD, closure/foreclosure fields, KYC/banking/reporting fields, dynamic installments.
- Report request: type, status, requester, LSP/date filters, notification email state, file metadata, error message, completion timestamps.

### Alerts

- Alert ID, type, severity, status, title, message, subject type/id, correlation ID, context JSON, created time, acknowledgment metadata.

## Forms And Controls

- Search controls appear in the shell header, loan ledger, and LSP loan view.
- Select controls are used for status, LSP, product, role, scope, webhook enabled state, and sort mode.
- Date pickers are used for report disbursal date ranges.
- Tabs are used for alert filters.
- Checkboxes are used for webhook event-type selection.
- Tables are used for applications, repayment schedules, borrower loans, alerts, MIS previews, and critical disbursements.
- Cards and sections are used for admin details, KPIs, profile panels, and summary blocks.

## Loading, Error, And Empty States

- Long-running page loads usually use `BlueLoader`.
- Generic loading/error/empty surfaces use `ContentState`.
- Admin pages use `AdminEmptyState` for empty, warning, permission, and form feedback.
- Permission failures are handled explicitly on admin pages where 401/403 are detected.
- Tables show empty content when lists are empty rather than rendering blank grids.
- Report queue history polls while background jobs are pending or processing.

## Current UI Notes And Gaps

- `/api-clients` is implemented but not currently present in the role-based sidebar navigation.
- `/dashboard` is retained only as a redirect to `/home`.
- Some dashboard trend labels and the month bar chart are static presentation elements combined with live portfolio values.
- Several older class names remain in `index.css` from an earlier shell style, while active pages also use Tailwind utility classes and app/admin primitives.
- Some source comments and text output show mojibake characters in the current files; the UI intent is still clear, but source encoding cleanup would improve maintainability.
