#!/usr/bin/env bash
# Creates one GitHub issue per technical item in the Bhawana LMS audit tracker.
#
# WHY THIS IS A SCRIPT AND NOT SOMETHING CLAUDE RAN:
#   The active gh account on this machine is the WORK account
#   (siddhant-daryanani_mlt). Creating these issues as sid12701 needs
#   `gh auth switch`, which mutates global machine state and silently changes
#   the identity of every later gh command. That is yours to run, not mine.
#
# USAGE:
#   gh auth switch -u sid12701          # do this yourself first
#   bash scripts/create-technical-issues.sh
#   gh auth switch -u siddhant-daryanani_mlt   # switch back when done
#
# The script is idempotent by title: it skips any item whose issue already exists.
# It appends "ID<TAB>URL" to scripts/.technical-issue-map.tsv for the tracker backfill.

set -euo pipefail

REPO="sid12701/lms"
MAP="scripts/.technical-issue-map.tsv"

if [ "$(gh api user --jq .login)" != "sid12701" ]; then
  echo "Active gh account is $(gh api user --jq .login), not sid12701." >&2
  echo "Run: gh auth switch -u sid12701" >&2
  exit 1
fi

touch "$MAP"

ensure_label() {
  gh label create "$1" --repo "$REPO" --color "$2" --description "$3" 2>/dev/null || true
}
ensure_label "ready-for-agent" "0e8a16" "Fully specified, ready for an AFK agent"
ensure_label "needs-triage"    "fbca04" "Maintainer needs to evaluate this issue"
ensure_label "audit-2026-07-31" "1d76db" "From the consolidated audit of 2026-07-31"

create_issue() {
  local id="$1" title="$2" label="$3" body_file="$4"
  if grep -q "^$id\t" "$MAP" 2>/dev/null; then
    echo "skip $id (already in $MAP)"; return
  fi
  local existing
  existing=$(gh issue list --repo "$REPO" --state all --search "\"$title\" in:title" \
              --json number,title --jq ".[] | select(.title==\"$title\") | .number" | head -1)
  if [ -n "$existing" ]; then
    echo "skip $id (issue #$existing already exists)"
    printf '%s\t%s\n' "$id" "https://github.com/$REPO/issues/$existing" >> "$MAP"
    return
  fi
  local url
  url=$(gh issue create --repo "$REPO" --title "$title" --label "$label" \
        --label "audit-2026-07-31" --body-file "$body_file")
  echo "created $id -> $url"
  printf '%s\t%s\n' "$id" "$url" >> "$MAP"
  sleep 1   # stay under the secondary rate limit
}

BODYDIR=$(mktemp -d)
trap 'rm -rf "$BODYDIR"' EXIT

cat > "$BODYDIR/C13.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C13` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C13 — 88% of backend tests run on H2 with Flyway off — no fix below is verifiable until this changes

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Critical |
| **Workstream** | WS-9 — Gate 0 — verification |
| **Effort** | 3 days |
| **Dependencies** | None. Every other technical item depends on it for verification — including C1, which you approved. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `backend/src/test/resources/application-test.yml:3,7-11,15-16` — H2 URL, flyway.enabled false, ddl-auto create-drop
- `tenant/TenantIsolationDataSourceConfig.java:34-37` — returns adminDataSource when the URL is not jdbc:postgresql
- `V111__disbursement_intent.sql:26-28` — the partial unique index that stops double disbursement exists only in the migration

**Files in scope:** `backend/src/test/resources/application-test.yml`, `tenant/TenantIsolationDataSourceConfig.java`, `V111__disbursement_intent.sql`

**Root cause**

Hibernate builds the test schema from the entities. The 113 migrations never run. So the tests have no RLS, no grants, no CHECK constraints, no partial indexes and no cascade rules. On H2 the tenant datasource becomes the admin datasource, so every tenancy test passes without a tenant. 93 of 765 test methods (12.2%) touch real Postgres, and Docker absence makes even those disappear while the build stays green.

**Implementation spec**

1. **Settled: Testcontainers Postgres 17 plus Flyway, and Docker is approved.** Make it the default datasource in `application-test.yml` and delete the H2 block. Use one singleton container with `withReuse(true)` and a tmpfs data directory, so the suite time stays close to today's.
2. Remove `disabledWithoutDocker = true` from `PostgresDataJpaTestSupport`. A build without Docker must fail loudly rather than skip 93 tests in silence.
3. Delete the three H2 branches in `AlertRuleSetQueryRepository:28-54`, `DisbursementIntentRepositoryImpl:28-32` and `PostgresAdvisoryLockSupport:21-24`. Production then has one code path.
4. Add a coverage gate to both builds, and run all 18 Playwright tests in CI instead of 1.
5. Add one test that asserts the tenant datasource and the admin datasource are different objects. That single test would have caught this whole class of defect.
6. **Pin the container to the Postgres major version Azure will run.** Testcontainers should match the target, not the newest release. Confirm the Azure Flexible Server version when that decision is made.
7. **Keep `IntegrationTestDatabaseTargetGuard` and extend it.** It refuses `supabase.co` today. Add the Azure host patterns (`postgres.database.azure.com`) at the same time, so the guard still holds when the environment changes.
8. **The managed-platform verification job stays on the plan.** It is re-aimed at Azure and scheduled with the Azure migration. See the note below.

**Do NOT do this** — considered and rejected

> **Point the test suite at a shared hosted database that holds real data**
> Three reasons that hold regardless of which cloud hosts it. The tests must drop and rebuild the schema to prove the 113 migrations produce it, and you cannot drop a database holding live loans. 765 tests writing and deleting would run against real borrower rows. And CI would hold credentials to live PAN, Aadhaar and bank numbers, which contradicts R5, H26 and H42 in this same audit. Your team already encoded this refusal in IntegrationTestDatabaseTargetGuard.

**Scale note**

A reused container plus tmpfs holds the full suite near 4 minutes. Parallel test classes share the container with a schema-per-class or truncate-between-tests strategy.

**Owner decision (recorded)** — Approved — Testcontainers, Docker allowed

You confirmed Supabase is only your local development convenience, that Azure Postgres is the real target for dev and production later, and that Docker is fine for tests. That settles it: Testcontainers plus Flyway, starting now. The managed-platform verification job is re-aimed at Azure and deferred until Azure exists. Part of my earlier argument was wrong — see the correction in the section above.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C13" "[C13] 88% of backend tests run on H2 with Flyway off — no fix below is verifiable until this changes" "ready-for-agent" "$BODYDIR/C13.md"

cat > "$BODYDIR/C1.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C1` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C1 — Idempotent LSP writes run with row-level security fully off

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Critical |
| **Workstream** | WS-1 — Tenancy |
| **Effort** | 2–3 days after C13 |
| **Dependencies** | C13 (otherwise unverifiable). Pairs with C2 and H34. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/IdempotencyExecutionCoordinator.java:346-370` — action.get() runs inside adminScopedTransactionExecutor.call
- `tenant/AdminScopedTransactionExecutor.java:34-40` — calls TenantDataAccessContextHolder.useAdmin() then REQUIRES_NEW
- `0 of 113 migrations contain FORCE ROW LEVEL SECURITY` — verified again in this pass

**Files in scope:** `service/IdempotencyExecutionCoordinator.java`, `tenant/AdminScopedTransactionExecutor.java`

**Root cause**

The LSP business write runs on the RLS-exempt admin pool. The same request without an `Idempotency-Key` header runs on the tenant pool with RLS on. Six endpoints behave in two different ways. Tenant isolation on those paths falls back to one Java `equals`. The table owner is also exempt from all 28 policies, because no table forces RLS.

**Implementation spec**

1. Add `ScopePreservingTransactionExecutor` to `com.bhawana.lms.tenant`. It uses the same REQUIRES_NEW template as `AdminScopedTransactionExecutor`, but it does not call `useAdmin()`. REQUIRES_NEW was the real requirement; the admin scope was never the requirement.
2. `executeClaimedLsp` uses the new executor. `executeClaimedAdmin` keeps the admin executor, because that path is genuinely admin.
3. This is safe today: `lsp_api_idempotency_record` already has RLS enabled (`V41:240`), already has a tenant policy on `lsp_id` (`V41:363`), and the tenant role already holds the four grants (`V41:220`). The ledger write needs no elevation.
4. Move the elevation that IS needed to the exact line that needs it. `BorrowerOnboardingService` elevates only for the cross-tenant PAN and mobile lookup, and for the visibility grant. Both are perimeter work, which ADR 0005 already sanctions. Every profile write then runs under tenant scope. See C2 and H34.
5. Add `V114__force_row_level_security.sql`. Use a DO block over `pg_class.relrowsecurity` so it forces RLS on every table that has it enabled. A future table cannot drift out of the set.
6. Add an architecture test: no class reachable from an LSP controller may call `AdminScopedTransactionExecutor` or `TenantScopedExecution.callAsAdmin` outside a named allowlist. The allowlist starts with the borrower perimeter lookups and nothing else.
7. Add a Postgres integration test: LSP-B replays LSP-A's request with a forged application id and gets zero rows, not a Java-level rejection.

**Do NOT do this** — considered and rejected

> **Snapshot the tenant context, elevate for the ledger write, restore for action.get() — the audit's proposal**
> It works, but it leaves two scopes interleaved inside one method, and the next author will break it again. Removing the elevation is smaller and the database already supports it.

**Scale note**

FORCE RLS adds no measurable cost. The policy performance issue is real and separate — see M2, which must land in the same quarter or the tenant pool slows down as the book grows.

**Owner decision (recorded)** — Approved

Approved as proposed: remove the elevation rather than manage it, add FORCE ROW LEVEL SECURITY, and narrow the borrower perimeter lookup. This also settles the shape of C2 and H34. I cannot verify it until C13 is settled — see the note on that card.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C1" "[C1] Idempotent LSP writes run with row-level security fully off" "ready-for-agent" "$BODYDIR/C1.md"

cat > "$BODYDIR/C2.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C2` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C2 — Any LSP can overwrite another LSP's borrower bank account, with no audit row

| | |
|---|---|
| **Status** | DEFERRED by the owner — do not start |
| **Severity** | Critical |
| **Workstream** | WS-1 — Tenancy / money |
| **Effort** | 3 days for the write-isolation guard. About 3 weeks for the full three-layer model with penny-drop verification. |
| **Dependencies** | C1 for the scope change. The identity spine is P3 and it is shared with R2 (CKYC). |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `needs-triage` |

**Evidence — read these first**

- `service/BorrowerOnboardingService.java:69,87-92` — findByPan is admin-scoped, then mergeLatestProfile copies caller values
- `domain/Borrower.java:330-345` — mergeLatestProfile passes bankAccountNumber, ifscCode and accountHolderName straight through; only aadharNumber is held
- `service/DisbursementIntentWorkflowService.java:98-99` — the payout beneficiary is snapshotted from that row

**Files in scope:** `service/BorrowerOnboardingService.java`, `domain/Borrower.java`, `service/DisbursementIntentWorkflowService.java`

**Root cause**

The borrower directory is global and the profile is last-writer-wins. LSP-B posts an application for a PAN that LSP-A already onboarded, supplies its own bank account, and the shared row changes. The active-loan guard does not fire, because no `loan_account` exists until approval. No `borrower_bank_details_update_audit` row is written, because that audit only fires on the dedicated endpoint.

**Implementation spec**

1. **DEFERRED on 2026-08-01.** Both options are written out below so the work can start the day you lift the deferral. Nothing here is built for now.
2. **Option A — write isolation only.** About 3 days. Steps 3 to 6 and 9 below, without any penny drop. It closes the live cross-tenant attack and touches no external provider. It leaves the borrower row shared and unverified.
3. **Option B — the full three-layer model with verification.** About 3 weeks. Every step below. It closes the class of defect and gives CKYC (R2) a home. It needs a bank-account verification provider, which is the part tied to a decision you have not made.
4. **Keep the global borrower directory.** The research says it is right. The industry pattern is UCIC / CDMS — one customer record, one source of truth, many loans linked to it. Bhawana is the RE and owns the customer relationship, so a per-LSP borrower copy would break exposure limits, dedup and cross-application fraud detection. The problem to solve is the write permission on that shared row, not the sharing itself.
5. **Split the row into three layers.** Layer 1, `borrower` — the identity spine that Bhawana owns: PAN, name as per PAN, date of birth, Aadhaar reference, CKYC KIN. Write-once, and changed only by a verified process, never by an LSP submission. Layer 2, `borrower_profile_claim(borrower_id, lsp_id)` — what each LSP submitted: address, employment, income and contact. Per-LSP, so two LSPs cannot conflict. Layer 3, `borrower_bank_account` — see the next step.
6. **Make the bank account a verified instrument, not a profile field.** This is the core of the research. Columns: account number, IFSC, holder name, `verification_status`, `verification_method`, `name_match_score`, `verified_at`, `submitted_by_lsp_id`, `is_active`. A borrower may have several accounts over time. An account is usable for disbursement only when it is VERIFIED.
7. **Verify with a penny drop plus a name match.** This is the standard Indian control and RBI has accepted it as part of KYC since 2016. It sends ₹1 over IMPS and returns the name the bank holds. Match that name against the borrower's PAN name and store the score. Since 1 April 2025 RBI also provides beneficiary name lookup before an NEFT or RTGS transfer, which gives a second check at payout time.
8. **The attack then closes by construction.** LSP-B submitting a different account for LSP-A's PAN no longer overwrites anything. It creates a new UNVERIFIED instrument. It cannot be used for a disbursement until a penny drop returns a name that matches the borrower's PAN name. A fraudulent account fails the name match, so it never becomes payable.
9. **Keep the beneficiary snapshot at disbursement.** `DisbursementIntentWorkflowService:98-99` already snapshots the beneficiary onto the intent, and the audit calls that correct. Add the verification evidence id to the snapshot, so the payout record proves which verification authorised it.
10. **A conflict goes to an operations queue, and it is also a fraud signal.** Reuse the existing `BORROWER_IDENTITY_CONFLICT` path. One PAN with several accounts across several LSPs is a documented ring-fraud indicator, so the queue earns its keep beyond this defect.
11. **Also extend `BorrowerActiveLoanChecker` to in-flight applications.** Today it needs a `loan_account`, which does not exist until approval. It must also see INITIALIZED and AWAITING_APPROVAL — that window is what the attack uses.

**Do NOT do this** — considered and rejected

> **Give each LSP its own borrower row, so there is nothing shared to overwrite**
> It removes the defect and breaks four things that matter more: exposure limits across LSPs, the active-loan duplicate check, CKYC search-before-collect, and cross-application fraud detection. The research is consistent that one customer master is the correct shape, so the write permissions are what should change.

**Scale note**

One row per (borrower, LSP) claim and a small number of accounts per borrower. The penny drop is an external API call with a cost per check, so cache a verified account and re-verify only on change or after a set age.

**Owner decision (recorded)** — Deferred

Deferred. Both routes are written into the spec above — Option A (write isolation only, about 3 days, no external provider) and Option B (the full three-layer model with account verification, about 3 weeks). Option B needs a verification provider, which is the ICICI-adjacent decision. Recorded so the work can start immediately when you lift this.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C2" "[C2] Any LSP can overwrite another LSP's borrower bank account, with no audit row" "needs-triage" "$BODYDIR/C2.md"

cat > "$BODYDIR/C3.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C3` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C3 — A deactivated user keeps full access forever by refreshing the token

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Critical |
| **Workstream** | WS-2 — Identity |
| **Effort** | 2 days |
| **Dependencies** | Pairs with C4 — the same policy object closes both. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/UserAdminService.java:184-217` — revocation fires on role change only; updateManagedProfile sets status and never touches tokenVersion
- `security/ManagedUserJwtPrincipalResolver.java` — validateSession checks pwdv and tv, and never reads snapshot.status()
- `service/AuthTokenService.java:163-201` — rotateRefreshToken mints a new token with no status check

**Files in scope:** `service/UserAdminService.java`, `security/ManagedUserJwtPrincipalResolver.java`, `service/AuthTokenService.java`

**Root cause**

The session validity rule is written in three places and each place knows a different part of it. `/api/v1/auth/refresh` is `permitAll`. A deactivated user with a refresh cookie renews every 30 minutes for as long as the 7-day window rolls. `ApiClientJwtSessionValidator:70-76` checks status correctly for machines. Humans have no equivalent.

**Implementation spec**

1. Write the rule once. Add `SessionValidityPolicy` with one method: given the token claims and the subject snapshot, return valid or a typed reason. It checks token version, password version, subject status and LSP status.
2. `ManagedUserJwtPrincipalResolver`, `ApiClientJwtSessionValidator` and `AuthTokenService.rotateRefreshToken` all call that one policy. No path can hold a different opinion after this.
3. Move the status write out of `updateManagedProfile` into `AppUser.changeStatus(UserStatus)`, which bumps `tokenVersion` itself. One writer, and no way to forget the bump.
4. `UserAdminService` calls `revokeAllSessions` on a status change with a new `RevocationSource.STATUS_CHANGE`, and evicts the principal cache. The LSP disable path already evicts correctly — copy that behaviour.
5. Add the failure reason to `auth_event_audit` so an operator can see why a refresh was refused.
6. Tests: deactivate a user, then (a) the current access token fails on the next request, (b) refresh returns 401, (c) the audit row names the reason.

**Scale note**

The policy runs on every request. It reads the cached snapshot, so it adds no query. Keep the cache TTL short enough that a revocation takes effect inside the operator's expectation — state the number in the ADR.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Session and identity kill chain". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C3" "[C3] A deactivated user keeps full access forever by refreshing the token" "ready-for-agent" "$BODYDIR/C3.md"

cat > "$BODYDIR/C4.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C4` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C4 — Disabling an LSP kills its API clients and leaves its human users signed in

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Critical |
| **Workstream** | WS-2 — Identity |
| **Effort** | 1 day on top of C3 |
| **Dependencies** | C3 — build them together. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/LspStatusService.java:93-115` — disable() iterates apiClientRepository.findByLsp_Id and touches no app_user row
- `security/ApiClientJwtSessionValidator:70-76` — checks lspStatus and apiClientStatus on every request
- `No LSP status check exists on the managed-user path`

**Files in scope:** `service/LspStatusService.java`

**Root cause**

An LSP_UI_READ or LSP_UI_WRITE principal carries an `lspId` claim but not `authType=API_CLIENT`, so the API client validator returns success immediately. "Kill this LSP" therefore stops the machine integration completely and stops nothing for the people. They can also sign in again with their password.

**Implementation spec**

1. Same `SessionValidityPolicy` as C3. It checks LSP status for any principal that carries an `lspId` claim, whether human or machine. The asymmetry then cannot exist.
2. Add `lspStatus` to `AuthPrincipalCache.AppUserSnapshot`, beside the status that is already there.
3. `LspStatusService.disable` also iterates `appUserRepository.findByLsp_Id(...)`, calls `revokeAllSessions()` on each, and evicts each from the principal cache. Record the user count in the LSP audit row beside the existing client count.
4. Refuse password sign-in for an LSP-bound user when the LSP is INACTIVE, in the authentication provider, with a distinct failure reason.
5. Do not change the users' own status. The LSP status and the user status are different facts. Reactivation then needs no repair step.
6. Test: disable an LSP, then assert that a UI user's live token fails, their refresh fails, and their password sign-in fails.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Session and identity kill chain". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C4" "[C4] Disabling an LSP kills its API clients and leaves its human users signed in" "ready-for-agent" "$BODYDIR/C4.md"

cat > "$BODYDIR/C6.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C6` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C6 — A borrower who never pays is never delinquent

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Critical |
| **Workstream** | WS-4 — Portfolio |
| **Effort** | 1 week including the day-end job |
| **Dependencies** | None hard. It is the base for §7.3 bureau reporting and for H14 SMA/NPA. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `repo/AlertRuleSetQueryRepository.java:126` — where app.status = 'UNDER_REPAYMENT'
- `service/LoanRepaymentCommandService.java:344-361` — transitionToUnderRepaymentIfNeeded is the only writer of that status, and only the payment path calls it

**Files in scope:** `repo/AlertRuleSetQueryRepository.java`, `service/LoanRepaymentCommandService.java`

**Root cause**

Delinquency is derived from a status that only a payment can set. A disbursed loan with no payment stays `DISBURSED` for ever. It is outside every DPD bucket, raises no alert, and gets no `loan_delinquency_state` row. It reaches 90+ DPD in silence. First-payment default is the strongest early fraud and credit signal in unsecured Indian retail lending, and this platform cannot see it.

**Implementation spec**

1. Derive delinquency from the schedule, never from the application status. The predicate is `installment.due_date < today AND outstanding_amount > 0`, over every account whose `loan_account.status` means live.
2. Define "live account" once. Add one repository method or one SQL fragment, and make the read API, the alert engine and the KPI snapshot all use it. Today there are three definitions and they disagree — that is H15, and it closes here for free.
3. Add the day-end job (H13) that writes a `loan_delinquency_state` row for every live loan each business day. The read paths then read a persisted state instead of three live recomputations.
4. Add a `first_payment_default` flag on that state, with its own ops alert rule. It is nearly free once the day-end job exists, and it is the finding's real point.
5. Keep `UNDER_REPAYMENT` as a display status if the operations team wants it, but no computation may read it.

**Scale note**

The day-end job scans live accounts once per day. At 50,000 accounts this is a single indexed pass. Add the composite index on `(loan_account_id, due_date)` with `CREATE INDEX CONCURRENTLY` — see M4.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Portfolio state and the day-end job". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C6" "[C6] A borrower who never pays is never delinquent" "ready-for-agent" "$BODYDIR/C6.md"

cat > "$BODYDIR/C7.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C7` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C7 — No payment reversal exists, and CLOSED cannot be reopened

| | |
|---|---|
| **Status** | DEFERRED by the owner — do not start |
| **Severity** | Critical |
| **Workstream** | WS-3 — Money |
| **Effort** | 1 week, inside the allocator work (H1) |
| **Dependencies** | Build it with H1. Separately they conflict. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `needs-triage` |

**Evidence — read these first**

- `domain/LoanPaymentStatus.java` — RECEIVED, PENDING_RECONCILIATION and FAILED are declared; only RECEIVED is ever written
- `domain/LoanApplicationStatus.java:46,67-74` — CLOSED is terminal as source and as target, and manual override refuses it
- `service/LoanServicingSupportService.java:249-252` — a dead branch shows the replay was designed for a non-received payment

**Files in scope:** `domain/LoanPaymentStatus.java`, `domain/LoanApplicationStatus.java`, `service/LoanServicingSupportService.java`

**Root cause**

**In plain terms: the platform can record that money arrived, and it cannot record that the money came back.** Every payment row is written as RECEIVED and no row can ever be marked as failed or undone.

Here is the sequence that breaks it. The borrower's last EMI is collected by NACH on Monday. The platform writes the payment as RECEIVED, the schedule closes, the loan account closes as FULLY_REPAID, the application moves to CLOSED, and the `LOAN_FULLY_REPAID` webhook goes to the LSP. On Wednesday the borrower's bank returns the mandate — there were no funds. The money never actually arrived.

Now nothing can be corrected. The payment row cannot be marked failed. CLOSED is defined as a terminal status, and the manual admin override refuses it as both a source and a target. The LSP has already been told the loan is repaid. The borrower owes one EMI and the platform says the loan is closed. The only repair is SQL in production.

There is a second reason this matters. The platform recalculates payment allocation by replaying every payment from the start. That replay can only correct a wrong split if it can reverse the original — which is how Fineract does it. Bhawana built the expensive half of that design and not the cheap half.

**Implementation spec**

1. Add `is_reversed`, `reversed_on_date`, `reversal_reason` and `reversed_by` to `loan_payment_transaction`. Use the flag model, as Fineract does on `m_loan_transaction`. Do not use a contra-row, because the allocator would then need negative amounts.
2. The replay query excludes reversed rows. That is one predicate in `recomputePaymentAllocation`, and it makes the dead branch at :249-252 live and correct.
3. Give `PENDING_RECONCILIATION` and `FAILED` real writers. A NACH presentation writes PENDING_RECONCILIATION. A bounce writes FAILED and the reversal.
4. Add one controlled transition `CLOSED → UNDER_REPAYMENT` that only the reversal command may request, with the mandatory reason code `PAYMENT_REVERSED`. Terminal stays terminal for every human path. `LoanAccount.reopen(reason)` mirrors it.
5. Emit a `LOAN_REOPENED` webhook, so an LSP that already received `LOAN_FULLY_REPAID` learns the truth. Ordering matters here — see H38.
6. Require maker-checker on a reversal (H27). A reversal moves money on the books.

**Do NOT do this** — considered and rejected

> **Handle a bounce by a manual admin override on the application status**
> It changes the status and leaves the payment row saying RECEIVED. Every later replay then re-closes the loan. The books stay wrong and the audit trail says an admin did it on purpose.

**Owner decision (recorded)** — Deferred

Deferred during the 2026-08-02 first-pass validation walk. Spec stays current. When lifted, build with H1 (allocator) — not alone. Maker-checker (H27) remains parked; do not ship production reversals without it.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C7" "[C7] No payment reversal exists, and CLOSED cannot be reopened" "needs-triage" "$BODYDIR/C7.md"

cat > "$BODYDIR/C9.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C9` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C9 — Every operator retry mints a new Idempotency-Key, so retries double-pay

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Critical |
| **Workstream** | WS-8 — Operations UI |
| **Effort** | 3 days |
| **Dependencies** | Fix with C10 and M15. Together they are the operator double-submit surface. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `frontend/src/lib/idempotency.ts:7-10` — newIdempotencyKey returns crypto.randomUUID()
- `57 references across the frontend; the call sits inside the submit handler or the mutationFn at every site`
- `features/loan-applications/api-detail.ts:497,601` — the two 'reuse if supplied' branches are never supplied a stable key

**Files in scope:** `frontend/src/lib/idempotency.ts`

**Root cause**

The key identifies the request, not the intent. A 504 after commit therefore looks like a failure, the operator clicks again, and the backend's unique constraint never fires. The result is two payments, or two disbursements, or two foreclosure executions. `ActionBar.tsx:104-112` keeps the dialog open on failure, which invites the second click. The doc comment states the defect as the intent: "Fresh BR-5 idempotency key minted at submit time."

**Implementation spec**

1. Change the contract: one key per user intent. Add `useIdempotencyKey(intentKey)` to `lib/idempotency.ts`. It mints on first use for that intent and holds the value in a ref.
2. Build the intent key from the action and the target, for example `disbursement-initiate:{applicationId}`. Two different applications get two different keys; two clicks on one application get one key.
3. Reset the key only on success, or when the operator explicitly abandons the action. A failed attempt must keep the key.
4. Delete the comments that describe minting at submit time. They teach the next author the defect.
5. Add a custom ESLint rule that forbids a direct `newIdempotencyKey()` call inside a `mutationFn` or a submit handler. Without the rule this returns within a quarter.
6. Test both sides: a frontend test that two submits send one key, and a backend integration test that the second request returns the first response.

**Scale note**

Also pin the backend retention window for idempotency records. Adyen's 7 days suits a system with bank reconciliation cycles better than Stripe's 24 hours. Decide whether 5xx responses are stored — I recommend not storing them.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Operator double-submit safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C9" "[C9] Every operator retry mints a new Idempotency-Key, so retries double-pay" "ready-for-agent" "$BODYDIR/C9.md"

cat > "$BODYDIR/C10.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C10` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C10 — The frontend silently converts a rejected transition into an admin override

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Critical |
| **Workstream** | WS-8 — Operations UI |
| **Effort** | 1 day |
| **Dependencies** | Do it with C9. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `frontend/src/features/loan-applications/api-detail.ts:441-447` — catches 400/403, checks isSystemAdmin(), re-posts to manual-status
- `api-detail.ts:457-474` — fills note with 'Manual override' and reasonCode with 'MANUAL_ADMIN_OVERRIDE'
- `api-detail.ts:38-40` — isSystemAdmin() reads the role from client-held session state

**Files in scope:** `frontend/src/features/loan-applications/api-detail.ts`

**Root cause**

An admin clicks a normal transition. The state machine correctly refuses it. The client hides the refusal, re-posts to the privileged endpoint, writes the justification itself, and reports success. The audit log then shows a deliberate manual override that no human chose, on the exact control an inspector examines. The same idempotency key is also reused across two different endpoints.

**Implementation spec**

1. Delete the fallback. Surface the 400 or 403 to the operator with the backend's message.
2. Make manual override a separate, deliberate control in the UI, visible only to a SYSTEM_ADMIN. It requires a free-text justification with no default value and a reason code the operator selects.
3. Never fabricate `note` or `reasonCode` in client code. Remove the default strings.
4. Give the override its own idempotency key. It is a different intent from the transition that failed.
5. Keep `isSystemAdmin()` for showing or hiding a control. It must never gate a call. Add a comment and a test that state this.
6. Test: a 400 from `status-transitions` reaches the operator, and no request goes to `manual-status`.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Operator double-submit safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C10" "[C10] The frontend silently converts a rejected transition into an admin override" "ready-for-agent" "$BODYDIR/C10.md"

cat > "$BODYDIR/C11.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C11` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C11 — A deploy with no profile boots on the repo-committed JWT secret, with the validator off

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Critical |
| **Workstream** | WS-9 — Deployment safety |
| **Effort** | 1 day |
| **Dependencies** | None. Do it in the first week. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `application.yml:10-11` — spring.profiles.default: local
- `application-local.yml:70` — ${APP_SECURITY_JWT_SECRET:local-dev-jwt-secret-at-least-32-characters}, 43 characters, passes the length check
- `config/UnsafeDeploymentConfigurationValidator.java:17,20-32` — DEV_ONLY_PROFILES contains local, so validation returns before it runs; the blocklist names a string that no longer exists in the repo

**Files in scope:** `application.yml`, `local.yml`, `config/UnsafeDeploymentConfigurationValidator.java`

**Root cause**

With `SPRING_PROFILES_ACTIVE` unset, `getDefaultProfiles()` returns `[local]` and the validator disarms itself. Two independent paths then lead to a forgeable SYSTEM_ADMIN token signed with a key that is in git. Insecure cookies and the hardcoded tenant password ride along. `TenantDatasourceSecurityValidator` has the identical bypass.

**Implementation spec**

1. Delete `spring.profiles.default` from `application.yml`. A boot with no profile then fails, which is the correct outcome.
2. Remove every `${VAR:fallback}` on a security-sensitive property in every profile: the JWT secret, the tenant database password, the cookie flags and the bootstrap admin password.
3. Replace the string blocklist with two checks that cannot go stale. First, reject a value whose property source is a classpath resource. Second, reject a value below a Shannon entropy threshold. Keep the length check.
4. Add a test that loads every committed YAML and asserts that no security property resolves to a value.
5. Remove the same profile bypass from `TenantDatasourceSecurityValidator`.
6. Add a `prod` profile that fails fast when any required secret is absent. This connects to H47.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Boot and deployment safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C11" "[C11] A deploy with no profile boots on the repo-committed JWT secret, with the validator off" "ready-for-agent" "$BODYDIR/C11.md"

cat > "$BODYDIR/C12.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `C12` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### C12 — The 11 custom metrics have no registry and no endpoint

| | |
|---|---|
| **Status** | DEFERRED by the owner — do not start |
| **Severity** | Critical |
| **Workstream** | WS-9 — Observability |
| **Effort** | 2 days |
| **Dependencies** | Pair with H44 (alert delivery). Neither is useful alone. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `needs-triage` |

**Evidence — read these first**

- `backend/pom.xml` — no micrometer-registry-prometheus in either POM (confirmed again in this pass)
- `application.yml:30-34` — actuator exposure is health,info
- `tenant/TenantIsolationDataSourceConfig.java:92-99,53` — health checks only the admin datasource; the tenant pool sets initializationFailTimeout(-1)

**Files in scope:** `backend/pom.xml`, `application.yml`, `tenant/TenantIsolationDataSourceConfig.java`

**Root cause**

Boot auto-configures a `SimpleMeterRegistry`, which is an in-memory sink discarded on restart. The instrumentation is well chosen — `lms.disbursement.intent.unknown.count` and `lms.disbursement.intent.unknown.oldest_age_seconds` are the two gauges that say money is in limbo — and nothing can ever read them. A misconfigured tenant role also boots green, reports healthy, passes readiness, and returns 500 on the first partner request.

**Implementation spec**

1. Add `micrometer-registry-prometheus` to `backend/pom.xml`.
2. Expose `prometheus` on a separate `management.server.port` bound to the private network. Do not expose it on the public port.
3. Add a health indicator for the tenant datasource. Remove `setInitializationFailTimeout(-1)`, or make the failure a readiness failure. A pool that cannot connect must not report ready.
4. Make `unknown.oldest_age_seconds` a paging SLO. Page when it goes above 900 seconds.
5. Add the standard JVM, Hikari and HTTP server metrics, which come free with the registry.

**Do NOT do this** — considered and rejected

> **Add the registry and stop there**
> A metric with no alert delivery is the same as no metric. This item is worth nothing without H44. Approve them together or defer both.

**Owner decision (recorded)** — Deferred

Deferred during the 2026-08-02 first-pass validation walk. Metrics/alerting stack is not chosen yet (Prometheus vs alternatives). Do not add a registry until that decision lands. H44 stays deferred with this item — metrics without alert delivery are not useful alone.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "C12" "[C12] The 11 custom metrics have no registry and no endpoint" "needs-triage" "$BODYDIR/C12.md"

cat > "$BODYDIR/H1.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H1` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H1 — Two allocation authorities that will diverge

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-3 — Money |
| **Effort** | 2 weeks — this is the centre of WS-3 |
| **Dependencies** | Build with C7 (reversal) and H3. They are one change. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/LoanServicingSupportService.java:240-275`

**Files in scope:** `service/LoanServicingSupportService.java`

**Root cause**

`applyPayment()` allocates to the single installment named by `repayment_installment_id`. `recomputePaymentAllocation()` resets everything and replays oldest-first, and ignores that foreign key. After the first recompute the key is affirmatively wrong. The exact-amount rule (H3) hides this today. Relax that rule without this fix and the divergence is immediate.

**Implementation spec**

1. Keep one authority. Make the incremental path a one-transaction case of the replay. Delete the named-installment allocation. Fineract's `processTransaction` takes no installment id, for this reason.
2. Add `loan_transaction_installment_allocation`: transaction id, installment id, and the four component portions. Rebuild it on every replay pass. Fineract's analogue is `m_loan_transaction_repayment_schedule_mapping`, and it is one-to-many, which the present single foreign key cannot express.
3. When a replay produces a different allocation from the stored one, record the change. Fineract reverses the original, inserts a replacement, links them and raises an event. Silent mutation leaves a borrower dispute with no answer.
4. Drop `repayment_installment_id` from the payment row, or keep it as a display hint that no computation reads. Do not keep it as an authority.

**Scale note**

The mapping table adds roughly one row per installment touched per payment. For a 24-month loan paid on time that is 24 rows. This is the shape every mature loan ledger uses.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Money core mechanics". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H1" "[H1] Two allocation authorities that will diverge" "ready-for-agent" "$BODYDIR/H1.md"

cat > "$BODYDIR/H2.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H2` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H2 — Money rounds to 2 decimals at every intermediate step

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-3 — Money |
| **Effort** | 1 week |
| **Dependencies** | Do it before H1's mapping table, so the mapping stores correct portions. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `common/money/Money.java:11-13` — scale(value) is setScale(2, HALF_UP), and it is a static utility, not a type
- `domain/LoanRepaymentScheduleInstallment.java:37-65` — every money column is precision 19, scale 2

**Files in scope:** `common/money/Money.java`, `domain/LoanRepaymentScheduleInstallment.java`

**Root cause**

Rounding at each step compounds over a 24 to 36 month schedule. This is why the partner-schedule validator needs an `interest-row-tolerance-abs` of 10.00 to accept schedules that should reconcile exactly. Fineract stores schedule money at scale 6. Mambu computes at 20 decimals and rounds only when it writes a journal entry.

**Implementation spec**

1. Make `Money` a value type, not a static helper. Hold the value plus a `MathContext`. Compute at 20 significant digits. Round once, at the persistence boundary.
2. Migrate the schedule money columns to `numeric(21,6)`. Keep the collectible EMI at 2 decimals, because that is what the bank rail moves.
3. Drop the validator tolerance from 10.00 to 0.01 once the precision is correct. The tolerance is a symptom; do not keep it after the cause is gone.
4. Add a golden-file test: a 36-month schedule reconciles to the rupee with no tolerance.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Money core mechanics". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H2" "[H2] Money rounds to 2 decimals at every intermediate step" "ready-for-agent" "$BODYDIR/H2.md"

cat > "$BODYDIR/H7.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H7` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H7 — The non-intent disbursement path calls the bank inside the transaction

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-5 — Disbursement |
| **Effort** | 1 day |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/LoanDisbursementCommandService.java:186-205`
- `The flag app.disbursement.intent-workflow.enabled selects it`

**Files in scope:** `service/LoanDisbursementCommandService.java`

**Root cause**

A crash between the provider call and the commit leaves money gone and no row at all. The worker then reissues with a fresh random `tranRefNo`. The correct path already exists beside it and is well built. The platform is one configuration flag away from the wrong one.

**Implementation spec**

1. Delete the legacy path and the flag. Keep one path.
2. Remove the flag from every profile and from the documentation, so it cannot return.
3. Add a boot-time assertion that fails if the property is present, for one release, then remove the assertion.

**Do NOT do this** — considered and rejected

> **Keep the flag as a fallback in case the intent workflow fails**
> The fallback is the unsafe path. A fallback that loses money is worse than an outage.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Disbursement paths". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H7" "[H7] The non-intent disbursement path calls the bank inside the transaction" "ready-for-agent" "$BODYDIR/H7.md"

cat > "$BODYDIR/H8.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H8` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H8 — Intents in REQUESTED or UNKNOWN can never be actioned

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-5 — Disbursement |
| **Effort** | 4 days |
| **Dependencies** | H27 for the approval. Ship the state machine first. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `domain/DisbursementIntentState.java:15-21` — CANCELLED exists and nothing writes it
- `service/DisbursementIntentWorkflowService.java:83-88` — DISBURSEMENT_ALREADY_REQUESTED is swallowed to a log.warn

**Files in scope:** `domain/DisbursementIntentState.java`, `service/DisbursementIntentWorkflowService.java`

**Root cause**

After the polls are exhausted the worker loops for ever: preflight passes, `createIntent` throws, the exception becomes a warning, and this repeats every 30 seconds. The only remedy today is SQL in production.

**Implementation spec**

1. Add the operator resolution path that writes CANCELLED. It needs the bank reconciliation reference and a maker-checker approval (H27), because it declares that money did not move.
2. Add a terminal `RESOLUTION_REQUIRED` state that the worker sets after the polls are exhausted. The worker then stops retrying and raises a CRITICAL ops alert.
3. Stop swallowing `DISBURSEMENT_ALREADY_REQUESTED`. Count it as a metric and alert after a small number of occurrences.
4. Write a `disbursement_intent_resolution` audit row for every manual resolution: who, when, the bank reference, and the reason.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Disbursement paths". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H8" "[H8] Intents in REQUESTED or UNKNOWN can never be actioned" "ready-for-agent" "$BODYDIR/H8.md"

cat > "$BODYDIR/H10.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H10` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H10 — Eligibility is evaluated against the mutable live product row

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-3 — Product |
| **Effort** | 2 days |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/LoanAutoApprovalRuleEngine.java:79-92`

**Files in scope:** `service/LoanAutoApprovalRuleEngine.java`

**Root cause**

Pricing correctly reads the frozen `LoanProductVersion`. Eligibility reads the live row. A configuration edit therefore changes the eligibility of applications that are already in flight, after they were submitted.

**Implementation spec**

1. Resolve the product version at intake and store the version id on the application.
2. Make the rule engine read the frozen version, the same as the six money-read call sites already do.
3. Add a test that changes the live product after intake and asserts the decision does not change.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Money core mechanics". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H10" "[H10] Eligibility is evaluated against the mutable live product row" "ready-for-agent" "$BODYDIR/H10.md"

cat > "$BODYDIR/H11.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H11` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H11 — The schedule is anchored on approval and never re-anchored at disbursement

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-3 — Money / conduct |
| **Effort** | 4 days |
| **Dependencies** | Fits with §7.1. Do them together. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/LoanRepaymentScheduleService.java:189,497`
- `service/LoanApplicationStatusWriter.java:122,146`

**Files in scope:** `service/LoanRepaymentScheduleService.java`, `service/LoanApplicationStatusWriter.java`

**Root cause**

The borrower pays interest from the sanction date, not from the date the money arrived. RBI's Fair Practices circular names this as an unfair practice.

**Implementation spec**

1. Generate a provisional schedule at approval. The KFS needs one, and the borrower must see the terms before acceptance.
2. Regenerate the schedule on the actual disbursement date, and make that version the live schedule.
3. Keep both. The KFS snapshot is evidence of what was disclosed; the live schedule is what the borrower owes. Store the KFS schedule as a JSON snapshot on `loan_kfs` (§7.1).
4. Alert when the gap between approval and disbursement is above a threshold, because a large gap changes the disclosed terms.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Money core mechanics". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H11" "[H11] The schedule is anchored on approval and never re-anchored at disbursement" "ready-for-agent" "$BODYDIR/H11.md"

cat > "$BODYDIR/H12.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H12` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H12 — The business date is computed in UTC while the clock is IST

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-3 — Correctness |
| **Effort** | 3 days |
| **Dependencies** | Do it before H13, so the day-end job has one definition of a business date. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `config/TimeConfig.java:11,15` — BUSINESS_ZONE Asia/Kolkata is used only to build the Clock bean
- `service/LoanRepaymentScheduleService.java:497 and service/AdminReportingService.java:324,328,351,359,399,407,431,432` — Instant to LocalDate conversions in UTC

**Files in scope:** `config/TimeConfig.java`, `service/LoanRepaymentScheduleService.java`, `service/AdminReportingService.java`

**Root cause**

The "today" path is correctly IST through `BusinessCalendar`. Every `Instant` to `LocalDate` conversion is UTC. A loan approved at 02:00 IST gets a schedule dated the previous day. The mix is the defect — a system that was UTC everywhere would at least be consistent.

**Implementation spec**

1. Add `BusinessDates.toBusinessDate(Instant)` and route every conversion through it.
2. Ban the raw forms. Add a forbidden-API or ArchUnit test that fails the build on `LocalDate.now()`, on `Instant.atZone(ZoneOffset.UTC)` and on `LocalDate.ofInstant(..., ZoneOffset.UTC)`, with an empty allowlist. This repo already uses that ratchet pattern for `@Lazy`, and it works.
3. Fix the eight reporting call sites and the schedule anchor in the same change.
4. Add a test that runs at a fixed clock of 20:30 UTC (02:00 IST next day) and asserts the business date.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Money core mechanics". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H12" "[H12] The business date is computed in UTC while the clock is IST" "ready-for-agent" "$BODYDIR/H12.md"

cat > "$BODYDIR/H13.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H13` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H13 — No day-end process exists

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-4 — Portfolio |
| **Effort** | 1 week for the framework, and each step lands into it |
| **Dependencies** | H12 must land first. C6, H14 and §7.3 land into it. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Zero @Scheduled(cron ...) in the codebase`


**Root cause**

RBI's November 2021 IRAC clarification requires SMA and NPA classification as part of the day-end process. Nothing here runs at day end. Every downstream obligation — classification, provisioning, bureau reporting — needs it first.

**Implementation spec**

1. Build one close-of-business pipeline that runs at IST day end, with ordered steps: interest accrual, overdue charge application, DPD and asset classification, delinquency state write, KPI snapshot, and bureau extract staging.
2. Make each step idempotent per business date. Add a `business_date_run` table with one row per date per step, so a failed run restarts from the failed step and never repeats a completed one.
3. Hold the scheduler singleton with the advisory lock pattern the repo already uses.
4. Alert when a business date has no completed run by a cut-off time. A silent day-end failure is the worst case.

**Scale note**

One indexed pass per live account per day. At 50,000 accounts this runs in seconds. Design it to be restartable from the start, because retrofitting restartability into a batch is expensive.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Portfolio state and the day-end job". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H13" "[H13] No day-end process exists" "ready-for-agent" "$BODYDIR/H13.md"

cat > "$BODYDIR/H14.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H14` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H14 — Buckets are generic 30-day bins with no SMA, NPA or provisioning

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-4 — Portfolio / regulatory |
| **Effort** | 1 week |
| **Dependencies** | H13. Blocks §7.3. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `domain/LoanDelinquencyBucket.java:3-9`

**Files in scope:** `domain/LoanDelinquencyBucket.java`

**Root cause**

The vocabulary is CURRENT, DPD_1_30, DPD_31_60, DPD_61_90, DPD_90_PLUS. RBI's vocabulary is SMA-0, SMA-1, SMA-2 and NPA, with a specific upgrade rule: after November 2021 an upgrade needs the clearance of all overdues, not only the oldest.

**Implementation spec**

1. Add `loan_asset_classification` with one durable row per loan per business date: DPD, SMA or NPA class, the amount overdue, and the provisioning input.
2. Write the rows from the day-end pipeline. Never derive the class at read time.
3. Implement the upgrade rule exactly: a loan leaves NPA only when every overdue amount is cleared.
4. Keep the existing buckets for the operations dashboard if the team wants them, and derive them from the classification, not beside it.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Portfolio state and the day-end job". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H14" "[H14] Buckets are generic 30-day bins with no SMA, NPA or provisioning" "ready-for-agent" "$BODYDIR/H14.md"

cat > "$BODYDIR/H15.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H15` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H15 — Three DPD computations over three different populations

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-4 — Portfolio |
| **Effort** | Included in C6 and H13 |
| **Dependencies** | C6, H13 |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/LoanDelinquencySupport.java:17-23` — any status
- `repo/AlertRuleSetQueryRepository.java:103-128` — UNDER_REPAYMENT only
- `service/PortfolioKpiSnapshotComputationService.java:171-191` — no status filter at all

**Files in scope:** `service/LoanDelinquencySupport.java`, `repo/AlertRuleSetQueryRepository.java`, `service/PortfolioKpiSnapshotComputationService.java`

**Root cause**

The KPI snapshot counts closed, foreclosed, invalid and never-disbursed accounts as CURRENT. So the dashboard and the alert engine report different portfolios on the same day, and PAR% is understated by the ratio of closed accounts to open ones.

**Implementation spec**

1. Closed by C6. One definition of a live account, one delinquency source, three readers.
2. After the day-end job exists, all three read the persisted `loan_asset_classification` row, so they cannot disagree even in principle.
3. Add a test that asserts the three surfaces return the same portfolio total for the same business date.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Portfolio state and the day-end job". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H15" "[H15] Three DPD computations over three different populations" "ready-for-agent" "$BODYDIR/H15.md"

cat > "$BODYDIR/H16.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H16` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H16 — MIS is as-of generation time, not as-of the period

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-9 — Reporting |
| **Effort** | 4 days |
| **Dependencies** | H13 and H14 — reproducibility needs the persisted classification rows. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/AdminReportingService.java:192,264,484`
- `service/PortfolioMisCsvWriter.java:37-90` — no as-of stamp, no timezone, no filter echo, no row count, no request id

**Files in scope:** `service/AdminReportingService.java`, `service/PortfolioMisCsvWriter.java`

**Root cause**

The same report re-runs with different numbers. An MIS that showed PAR-30 of 4.1% reproduces at 6.8% months later, and nothing proves which figure was reported. For a regulated lender this is the difference between a report and an assertion.

**Implementation spec**

1. Take an as-of business date as a required parameter. Read the persisted classification rows for that date, not the live tables.
2. Add a header block to every file: as-of date, timezone, every filter value, the row count, the request id, the generation time and the generating user.
3. Store the parameters and a SHA-256 of the produced file on `report_request`. A re-run then either matches the hash or proves the data changed.
4. State the reproducibility rule in the runbook: a report for a past business date must always reproduce byte for byte.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Reporting correctness". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H16" "[H16] MIS is as-of generation time, not as-of the period" "ready-for-agent" "$BODYDIR/H16.md"

cat > "$BODYDIR/H17.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H17` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H17 — The MIS preview is an unaudited bulk PII export

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — Reporting / PII |
| **Effort** | 2 days |
| **Dependencies** | None. This is cheap and it closes a real exposure. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `web/ReportAdminController.java:58-72` — previewPortfolioMisReport writes nothing to report_access_audit

**Files in scope:** `web/ReportAdminController.java`

**Root cause**

Only the download paths are audited. The preview returns name, address, IFSC, income and account number at 500 rows a page, the page number is unbounded, and the rate limit is 60 requests a minute. That is 30,000 PII rows a minute with no trace.

**Implementation spec**

1. Audit the preview into `report_access_audit`, with the same fields the download path writes, plus the row count returned.
2. Cap the page size at 50 for the preview and bound the total offset. A preview is for checking the shape of a report, not for extracting it.
3. Apply a separate, much lower rate limit to the preview.
4. Require a purpose field on the request and store it in the audit row.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Reporting correctness". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H17" "[H17] The MIS preview is an unaudited bulk PII export" "ready-for-agent" "$BODYDIR/H17.md"

cat > "$BODYDIR/H18.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H18` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H18 — The report worker holds a whole batch in one transaction and can wedge permanently

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-9 — Reporting / workers |
| **Effort** | 1 week |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/ReportRequestService.java:99-160` — @Transactional spans CSV generation, the R2 PUT and the SMTP send
- `service/AdminReportingService.java:243-267` — the whole CSV is built in a StringBuilder, then a String, then a byte array

**Files in scope:** `service/ReportRequestService.java`, `service/AdminReportingService.java`

**Root cause**

The catch clause takes `RuntimeException` and therefore does not catch `OutOfMemoryError`. On an out-of-memory failure the batch rolls back, completed reports return to PENDING with orphaned objects in R2, and the worker re-claims the identical batch every 15 seconds for ever. `PROCESSING` is not in the claim set and there is no lease, so a stranded row is never reclaimed.

**Implementation spec**

1. Take the R2 upload and the SMTP send out of the transaction. Only the state changes stay transactional.
2. Stream the CSV to R2 with a multipart upload. Never hold the whole file in memory. Resident memory today is about four times the file size.
3. Process one report per transaction, with a try/catch for each. One failing report must not roll back the others.
4. Add a lease with an expiry to `PROCESSING`, and add PROCESSING with an expired lease to the claim set. The outbox in this repo already does this correctly — copy that pattern.
5. Catch `Throwable` at the worker boundary, mark the row FAILED with the reason, and stop.
6. Add a row cap and reject a report request above it at submission time, with a clear message.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Reporting correctness". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H18" "[H18] The report worker holds a whole batch in one transaction and can wedge permanently" "ready-for-agent" "$BODYDIR/H18.md"

cat > "$BODYDIR/H19.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H19` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H19 — No tamper evidence of any kind

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — Evidence |
| **Effort** | 2 weeks |
| **Dependencies** | Do H20 and H21 first; they are cheap and they remove the deletion path. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Codebase-wide: no hash chain, no sequence number, no signature, no WORM sink`


**Root cause**

Audit tables cascade-delete from their business parents and the tenant role holds UPDATE and DELETE. One `DELETE FROM loan_application` erases an application's whole audit history and leaves no residue. MCA Rule 3(1) asks the company to show that the audit trail cannot be disabled or edited. Today the honest answer is that it can.

**Implementation spec**

1. Add a hash chain per audit stream: each row holds `prev_row_hash` and `row_hash`, computed over the canonical serialisation of the row. A deletion or an edit then breaks the chain.
2. Write a daily anchor: the last hash of each stream, with the business date, into a separate anchor table, and copy the anchor to an external append-only store.
3. Add a verification job that walks each chain and raises a CRITICAL alert on a break.
4. Add the external sink: R2 with Object Lock in compliance mode, written by a credential that has no delete permission. See H26.
5. Combine with H20 and H21. The three together are the answer to the Rule 3(1) question.

**Scale note**

The chain costs one hash per audit write. The audit volume the team projects is 50 to 150 million rows a year, so partition the tables by month at the same time (§7.5).

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H19" "[H19] No tamper evidence of any kind" "ready-for-agent" "$BODYDIR/H19.md"

cat > "$BODYDIR/H20.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H20` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H20 — ON DELETE CASCADE runs from business parents into audit tables

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — Evidence |
| **Effort** | 2 days |
| **Dependencies** | Do it before any erasure work (§7.5). |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `V9, V8, V11, V102, V54, V55, V5` — loan_application_status_transition, loan_application_intake_audit, loan_application_assignment_event, borrower_pii_reveal_audit, app_user_audit_event, api_client_audit_event, loan_product_audit_event


**Root cause**

Delete an administrator and the record of what they did goes with them. This is latent today, because no application code hard-deletes those parents. It becomes live on the day the DPDP erasure path is built — which is the exact day the evidence matters most.

**Implementation spec**

1. Change every audit foreign key to `ON DELETE RESTRICT`.
2. Where the audit row must outlive its parent, hold the parent id as a plain column with no foreign key, plus a denormalised copy of the identifying fields.
3. Add a schema test that fails when any table whose name ends in `_audit`, `_audit_event` or `_transition` has a cascading foreign key.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H20" "[H20] ON DELETE CASCADE runs from business parents into audit tables" "ready-for-agent" "$BODYDIR/H20.md"

cat > "$BODYDIR/H21.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H21` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H21 — The tenant database role holds UPDATE and DELETE on audit and financial evidence tables

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — Evidence |
| **Effort** | 1 day |
| **Dependencies** | None. Do this in the first week — it is one migration and one test. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `V41__tenant_isolation_rls.sql:206-215` — blanket SELECT, INSERT, UPDATE, DELETE on eleven tables
- `V42:17` — grants only SELECT, INSERT on the PII-reveal audit

**Files in scope:** `V41__tenant_isolation_rls.sql`

**Root cause**

V42 shows the team knows the right answer. V41 applied a template eleven times. An append-only table that the application role can update is not append-only.

**Implementation spec**

1. Add a migration that revokes UPDATE and DELETE from the tenant role on every audit and evidence table, and leaves SELECT and INSERT.
2. Add a schema test that asserts the grant set for every audit table. This is the ratchet that stops the next template copy.
3. Review the same grants for the admin role. The admin role needs UPDATE on business tables and does not need it on audit tables.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H21" "[H21] The tenant database role holds UPDATE and DELETE on audit and financial evidence tables" "ready-for-agent" "$BODYDIR/H21.md"

cat > "$BODYDIR/H22.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H22` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H22 — The audit explorer masks Aadhaar and nothing else

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — PII |
| **Effort** | 4 days |
| **Dependencies** | Shares the registry with M6, M7 and H17. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/AuditExplorerService.java:31-37,373-391` — a five-name allowlist
- `web/AuditExplorerController.java:37-68` — reading the audit log is itself unaudited and unthrottled

**Files in scope:** `service/AuditExplorerService.java`, `web/AuditExplorerController.java`

**Root cause**

`borrowerPan` and `bankAccountNumber` pass through untouched. One request for 500 intake events returns full PAN, bank account, IFSC, address, date of birth and income for every loan in the window.

**Implementation spec**

1. Replace the name allowlist with a field classification registry: each field carries a PII class, and the class decides the masking. A new field is masked by default until it is classified.
2. Make an unmasked read a separate, explicit action that goes through the existing PII-reveal flow and writes a reveal audit row.
3. Audit every audit-explorer read into `report_access_audit`, with the stream, the filter and the row count.
4. Rate-limit the explorer separately from the rest of the admin surface.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H22" "[H22] The audit explorer masks Aadhaar and nothing else" "ready-for-agent" "$BODYDIR/H22.md"

cat > "$BODYDIR/H23.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H23` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H23 — The audit explorer covers 8 of 15 audit tables

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — Evidence |
| **Effort** | 3 days |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/AuditExplorerQuery.java:31-40` — missing auth_event_audit, lsp_audit_event, borrower_bank_details_update_audit, borrower_pii_reveal_audit, webhook_outbox_redrive_audit, loan_application_pii_reveal_audit, loan_application_status_transition

**Files in scope:** `service/AuditExplorerQuery.java`

**Root cause**

An investigator who uses the explorer sees a partial history and has no signal that it is partial. The missing streams include the bank-detail change audit, which is the one that matters for C2.

**Implementation spec**

1. Register the seven missing streams.
2. Add a test that enumerates audit tables from the schema and fails when one has no registered stream. New audit tables then cannot drift out of the explorer.
3. Show the covered stream list in the response, so an investigator can see the scope of what they searched.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H23" "[H23] The audit explorer covers 8 of 15 audit tables" "ready-for-agent" "$BODYDIR/H23.md"

cat > "$BODYDIR/H24.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H24` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H24 — A product interest-rate change writes an audit row that does not contain the rate

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — Evidence |
| **Effort** | 2 days |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/ProductConfigurationService.java:188-197`
- `domain/LoanProductAuditEvent.java:20-41`

**Files in scope:** `service/ProductConfigurationService.java`, `domain/LoanProductAuditEvent.java`

**Root cause**

A change from 18% to 36% produces the sentence "Updated product PL-01 to ACTIVE with principal 10000-500000 and tenure 3-24 months." There is no rate, no previous value and no actor IP. The audit row for the most sensitive product change holds none of the change.

**Implementation spec**

1. Store a before-and-after JSON snapshot of the whole product version, not a rendered sentence. Render the sentence at read time.
2. Add the actor IP and the correlation id, as `LspAuditEventService` already does.
3. Use `LspAuditEventService` and `BorrowerBankDetailsService` as the pattern. The audit calls them the model the rest of the codebase should copy, and I agree.
4. Add a test that changes only the rate and asserts the audit row contains both values.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H24" "[H24] A product interest-rate change writes an audit row that does not contain the rate" "ready-for-agent" "$BODYDIR/H24.md"

cat > "$BODYDIR/H25.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H25` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H25 — Document replacement orphans the previous evidence

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — Evidence |
| **Effort** | 4 days |
| **Dependencies** | Deletion needs H26 and §7.5. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `domain/LoanApplicationDocumentChecklist.java:227-237` — update() overwrites storageKey, fileChecksum, fileName and fileSizeBytes in place

**Files in scope:** `domain/LoanApplicationDocumentChecklist.java`

**Root cause**

The previous object still exists in R2 and nothing points to it. No audit row records that a replacement happened. A KYC document can therefore be swapped and the platform holds no evidence of the earlier one.

**Implementation spec**

1. Never overwrite. Insert a new document version row and mark the previous one superseded.
2. Keep the previous storage key. The retention policy decides when it is removed, not a business action.
3. Write a replacement audit row: who, when, the old checksum and the new checksum.
4. Show the version history on the ops document view, so an investigator can see the replacements.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H25" "[H25] Document replacement orphans the previous evidence" "ready-for-agent" "$BODYDIR/H25.md"

cat > "$BODYDIR/H26.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H26` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H26 — Zero storage deletes, no object lock, no versioning, no lifecycle

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-6 — Storage |
| **Effort** | 1 week, plus the bucket configuration work |
| **Dependencies** | §7.5 retention policy defines the periods. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/R2*StorageService.java` — grep for deleteObject or Files.delete returns zero
- `One credential both writes and deletes`


**Root cause**

The platform can neither perform a DPDP erasure nor prove PMLA retention. These are opposite obligations and both need explicit control of the object lifecycle. One credential with both rights also means one leaked key can erase the evidence.

**Implementation spec**

1. Turn on versioning and Object Lock in compliance mode on the evidence bucket. Set the retention to the PMLA period.
2. Split the credentials: one write-only credential for the application, one read-only credential for retrieval, and one restricted credential used only by the retention worker.
3. Add a lifecycle rule that expires generated MIS files at 30 to 90 days. Those files contain the whole book and today they live for ever.
4. Add an explicit deletion path that only the retention worker can call, and that writes an audit row for each object it removes.
5. Verify the live bucket configuration. The audit could only read what the application asks for, not what the bucket does.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H26" "[H26] Zero storage deletes, no object lock, no versioning, no lifecycle" "ready-for-agent" "$BODYDIR/H26.md"

cat > "$BODYDIR/H28.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H28` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H28 — The bootstrap admin is re-created and re-granted SYSTEM_ADMIN on every boot

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-2 — Identity |
| **Effort** | 2 days |
| **Dependencies** | Do it with C11 in the deployment-safety batch. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/LocalBootstrapAdminSyncService.java:73-76,122-131` — no @Profile guard and no audit row
- `config/SecurityConfig:45-53` — returns an in-memory UserDetails whose token can never be revoked

**Files in scope:** `service/LocalBootstrapAdminSyncService.java`

**Root cause**

Disabling that account, or removing its roles, is reverted at the next deploy. The startup path writes no audit row, so the re-grant is invisible. The in-memory user is outside the whole session model.

**Implementation spec**

1. Guard the synchronisation with `@Profile("local")`. Outside local it must not run.
2. For the first production boot, use a one-time bootstrap that records that it ran, and refuses to run again.
3. Write an audit row for the startup path, with a synthetic system actor.
4. Delete the in-memory `UserDetails`. Every principal must be revocable.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Session and identity kill chain". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H28" "[H28] The bootstrap admin is re-created and re-granted SYSTEM_ADMIN on every boot" "ready-for-agent" "$BODYDIR/H28.md"

cat > "$BODYDIR/H29.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H29` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H29 — A password change does not revoke refresh tokens, and a comment claims it does

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-2 — Identity |
| **Effort** | Half a day |
| **Dependencies** | C3 |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/UserAdminService.java:235-240`
- `domain/AppUser.java:158-162` — changePassword sets passwordChangedAt and never touches tokenVersion

**Files in scope:** `service/UserAdminService.java`, `domain/AppUser.java`

**Root cause**

Access tokens die because `pwdv` changes. Refresh tokens live for the remaining seven days. So a user who changes their password after a compromise does not end the attacker's session.

**Implementation spec**

1. Make `changePassword` bump `tokenVersion`. One line, in the entity, where the invariant belongs.
2. The `SessionValidityPolicy` from C3 then rejects the old refresh token as well.
3. Correct the comment. A comment that states a guarantee the code does not give is worse than no comment.
4. Test: change the password, then assert that the old refresh cookie returns 401.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Session and identity kill chain". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H29" "[H29] A password change does not revoke refresh tokens, and a comment claims it does" "ready-for-agent" "$BODYDIR/H29.md"

cat > "$BODYDIR/H30.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H30` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H30 — Lockout is a 5-minute batch job, permanent until an admin resets it, and blind to distributed attacks

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-2 — Identity |
| **Effort** | 1 week |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/AlertRuleSchedulerWorker.java:31`
- `service/AlertRuleEvaluationWorker.java:281-302,335-380` — grouped by (username, actorIp)
- `service/ApiClientLockoutService` — does this correctly and synchronously for machine clients

**Files in scope:** `service/AlertRuleSchedulerWorker.java`, `service/AlertRuleEvaluationWorker.java`

**Root cause**

Grouping by IP means a rotating source defeats the lock, and the distributed rule only raises an alert. In the other direction, five requests permanently lock any known account, with no guard on the last active administrator. An attacker can therefore lock out the operations team.

**Implementation spec**

1. Make the lock synchronous at the authentication path, as `ApiClientLockoutService` already does for machines. A five-minute batch is not a lockout.
2. Key the account lock on the username only. Keep a separate IP-based rule for the distributed case, and make it block, not only alert.
3. Add an automatic unlock after a cool-down, with an increasing delay. Keep the administrator reset for the manual case.
4. Refuse to lock the last active SYSTEM_ADMIN. Raise a CRITICAL alert instead.
5. Add a global rule: many failures across many accounts from one network raises an alert and enables a stricter mode.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Session and identity kill chain". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H30" "[H30] Lockout is a 5-minute batch job, permanent until an admin resets it, and blind to distributed attacks" "ready-for-agent" "$BODYDIR/H30.md"

cat > "$BODYDIR/H31.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H31` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H31 — Six tenant-attributable tables have no row-level security at all

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-1 — Tenancy |
| **Effort** | 3 days |
| **Dependencies** | C1 and C13. The test is worthless on H2. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `V111 disbursement_intent, V101 loan_delinquency_state, V109 portfolio_kpi_snapshot, V78 borrower_bank_details_update_audit, loan_disbursement_bank_mismatch_log, V102 borrower_pii_reveal_audit`
- `Also webhook_event_delivery_attempt, disbursement_outcome_audit, report_access_audit, lsp_audit_event`


**Root cause**

About 30 migrations added tenant tables after V41. Five of them added RLS. The pattern is drift, not a decision. `disbursement_intent` is the most serious of the six, because it holds the beneficiary snapshot.

**Implementation spec**

1. Add RLS and a policy to each of the ten tables, in one migration, beside the FORCE RLS migration from C1.
2. Add the schema test that closes the class of defect: enumerate every table that holds an `lsp_id` column or a foreign key to a tenant-scoped table, and fail the build when one has no policy. New tables then cannot drift.
3. Where the tenant path never reads a table, grant nothing rather than adding a policy. Not every table needs a policy; every table needs a decision.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Tenancy completion". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H31" "[H31] Six tenant-attributable tables have no row-level security at all" "ready-for-agent" "$BODYDIR/H31.md"

cat > "$BODYDIR/H32.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H32` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H32 — The tenant database role can read password_hash and secret_hash

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-1 — Tenancy |
| **Effort** | Half a day |
| **Dependencies** | Verify with C13's real-Postgres tests that nothing breaks. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `V41__tenant_isolation_rls.sql:216-217` — GRANT SELECT on app_user and api_client

**Files in scope:** `V41__tenant_isolation_rls.sql`

**Root cause**

No tenant-scoped code path queries those tables. The grants are vestigial. A SQL injection or a compromised tenant connection reaches credential material that it never needs.

**Implementation spec**

1. Revoke SELECT on `app_user` and `api_client` from the tenant role.
2. If a tenant read is needed later, add a view that excludes the hash columns and grant on the view.
3. Add the grant assertion test from H21, which covers this table set as well.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Tenancy completion". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H32" "[H32] The tenant database role can read password_hash and secret_hash" "ready-for-agent" "$BODYDIR/H32.md"

cat > "$BODYDIR/H33.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H33` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H33 — OPS_USER can create loan applications for any LSP with no tenant check

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-1 — Access control |
| **Effort** | 1 day after the answer |
| **Dependencies** | Open question 3 |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `web/LoanApplicationOpsController.java:351-371` — no method-level @PreAuthorize
- `service/LoanApplicationOnboardingService.java:79-81,110-113` — the two-argument overload passes enforcedLspId = null and skips the check

**Files in scope:** `web/LoanApplicationOpsController.java`, `service/LoanApplicationOnboardingService.java`

**Root cause**

Every money-touching sibling in the same controller carries a SYSTEM_ADMIN restriction. This one does not. It is either an intended operations capability that nobody wrote down, or an omission.

**Implementation spec**

1. Answer open question 3 first. The fix depends on the answer.
2. If it is not intended: add `@PreAuthorize` for SYSTEM_ADMIN, and delete the null-lspId overload so the check cannot be skipped.
3. If it is intended: keep the capability, add an explicit `@PreAuthorize` that names it, require an explicit target LSP in the request, write an audit row that records the acting-for relationship, and document it in the ADR.
4. In both cases, delete the overload that makes the check optional. An enforced check must not have an opt-out signature.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Tenancy completion". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H33" "[H33] OPS_USER can create loan applications for any LSP with no tenant check" "ready-for-agent" "$BODYDIR/H33.md"

cat > "$BODYDIR/H34.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H34` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H34 — LSP bank-detail writes and application creation run admin-scoped

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-1 — Tenancy |
| **Effort** | Included in C1 |
| **Dependencies** | C1 |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/BorrowerBankDetailsService.java:102-117,253-256`
- `ADR 0005 sanctions elevation for principal resolution and perimeter lookups`

**Files in scope:** `service/BorrowerBankDetailsService.java`

**Root cause**

These are business writes, not perimeter lookups. Isolation therefore rests on a single Java `equals`, exactly as in C1. ADR 0005 already draws the correct line; the code crosses it.

**Implementation spec**

1. Apply the C1 design: elevate for the lookup only, and return a read-only projection. Perform the write under tenant scope.
2. Add the elevation allowlist test from C1. `BorrowerBankDetailsService` appears in it for the lookup and not for the write.
3. Add a note to ADR 0005 that names the distinction with these two examples, so the next author has a concrete rule and not only a principle.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Tenancy completion". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H34" "[H34] LSP bank-detail writes and application creation run admin-scoped" "ready-for-agent" "$BODYDIR/H34.md"

cat > "$BODYDIR/H35.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H35` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H35 — No rate limit on any LSP read endpoint

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 3 days |
| **Dependencies** | Gate item — the audit blocks the partner pilot on this one. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `application.yml:134-138,164-168` — the only GET rule covers documents

**Files in scope:** `application.yml`

**Root cause**

`GET /lsp/loan-applications?limit=200&offset=N` returns full borrower PII, 200 rows a call, with no limit on the rate. There is also no per-request access log, so a breach cannot be bounded after the fact.

**Implementation spec**

1. Apply a per-client rate limit to every LSP endpoint, reads included. Set the read limit from the partner's real integration need, not from a default.
2. Cap `limit` at the server. A client-supplied 200 must not be honoured without a server ceiling.
3. Add a per-request partner access log: client id, endpoint, filter, row count, and correlation id. This is what bounds a breach.
4. Add a volume alert per client per day, so an unusual extraction raises an alert on the day it happens.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H35" "[H35] No rate limit on any LSP read endpoint" "ready-for-agent" "$BODYDIR/H35.md"

cat > "$BODYDIR/H36.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H36` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H36 — X-Forwarded-For is trusted from the raw header

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 2 days |
| **Dependencies** | Needs the production network topology. Confirm it before the pilot. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `common/web/ClientIpAddresses.java:16-23`
- `security/LspSurfaceIpAllowlistFilter.java:70`

**Files in scope:** `common/web/ClientIpAddresses.java`, `security/LspSurfaceIpAllowlistFilter.java`

**Root cause**

The LSP IP allowlist is the control that a partner's security team signed off. A client that sends its own `X-Forwarded-For` header bypasses it. The forged address is then recorded in `BorrowerPiiRevealAudit`, so the audit trail records the attacker's chosen value.

**Implementation spec**

1. Configure the trusted proxy set explicitly. `server.forward-headers-strategy` is already NATIVE, so pair it with Tomcat's `RemoteIpValve` and an explicit `internalProxies` pattern.
2. Parse the header from the right, and skip only trusted hops. Never take the leftmost value.
3. When no trusted proxy is configured, ignore the header completely and use the socket address.
4. Add a test that sends a forged header from an untrusted source and asserts the allowlist still refuses it.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H36" "[H36] X-Forwarded-For is trusted from the raw header" "ready-for-agent" "$BODYDIR/H36.md"

cat > "$BODYDIR/H37.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H37` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H37 — One webhook signing secret per LSP, with no rotation grace

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 3 days |
| **Dependencies** | Gate item for the partner pilot. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `V23:4` — a single column
- `service/LspDirectoryService.java:169-175` — rotation is a PUT on that column
- `service/WebhookOutboxDispatchExecutor.java:357-365` — a 401 is classified PERMANENT_FAILURE

**Files in scope:** `service/LspDirectoryService.java`, `service/WebhookOutboxDispatchExecutor.java`

**Root cause**

A partner cannot rotate a secret without dropped events. In-flight events dead-letter immediately on a 401 instead of retrying. There is also no minimum entropy and `http://` endpoints are accepted. The same codebase gets this right for API client secrets, with a previous hash and a validity window.

**Implementation spec**

1. Copy the API client pattern: add `previous_secret_hash` and `previous_secret_valid_until`. Sign with the current secret and accept both during the window.
2. Reclassify 401 and 403 as retryable for a bounded period. A partner deploy in progress must not dead-letter the queue.
3. Require a minimum entropy on the secret and require an `https://` endpoint.
4. Give the partner a rotation endpoint that returns the new secret once, and starts the grace window automatically.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H37" "[H37] One webhook signing secret per LSP, with no rotation grace" "ready-for-agent" "$BODYDIR/H37.md"

cat > "$BODYDIR/H38.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H38` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H38 — Webhook ordering is not guaranteed for a single loan

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 4 days |
| **Dependencies** | C7 needs it, because LOAN_REOPENED must not overtake LOAN_FULLY_REPAID. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/WebhookOutboxService.java:181-184` — the claim query orders by created_at, then executor.submit discards the order across a 10-thread pool

**Files in scope:** `service/WebhookOutboxService.java`

**Root cause**

`DISBURSEMENT_COMPLETED` can reach the partner before `LOAN_STATUS_CHANGED → DISBURSED`. There is also no sequence number in the envelope, so the partner cannot detect the reordering or a gap.

**Implementation spec**

1. Add an ordering key to each event, which is the loan application id. Claim by key, and let one worker hold all events for one key at a time. Different loans still run in parallel.
2. Add a monotonic `sequence_number` per ordering key to the envelope, so a partner can order events and detect gaps.
3. Document the guarantee in the partner contract: ordered per loan, unordered across loans, at-least-once delivery.
4. Add a test that enqueues two events for one loan and asserts the delivery order under a concurrent pool.

**Scale note**

Keying by loan keeps parallelism high, because the key space is the book size. A global order would remove the parallelism and is not needed.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H38" "[H38] Webhook ordering is not guaranteed for a single loan" "ready-for-agent" "$BODYDIR/H38.md"

cat > "$BODYDIR/H39.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H39` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H39 — Payment idempotency keys are globally unique and guarded by a JVM-local lock

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 4 days |
| **Dependencies** | C1 changes the coordinator; do this after it. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `V92:6-7` — the unique constraint is global
- `service/LoanRepaymentCommandService.java:167` — synchronized (idempotencyKey.intern())

**Files in scope:** `service/LoanRepaymentCommandService.java`

**Root cause**

The system explicitly assumes several instances, so a JVM-local lock guarantees nothing. A cross-tenant key collision is invisible to RLS but not to the constraint, so it returns 500 and permanently poisons that key. The LSP API table gets this right with `(lsp_id, operation_key, idempotency_key)`.

**Implementation spec**

1. Change the constraint to `(lsp_id, operation_key, idempotency_key)`, the same shape the LSP table already uses.
2. Delete the `synchronized` block. Concurrency control belongs in the database, through the unique constraint and the existing lease machinery.
3. Route the payment path through the same `IdempotencyExecutionCoordinator` the LSP surface uses. Two idempotency implementations in one codebase will drift.
4. Migration: the old global keys must be re-scoped. Backfill the `lsp_id` from the loan account before the constraint change.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H39" "[H39] Payment idempotency keys are globally unique and guarded by a JVM-local lock" "ready-for-agent" "$BODYDIR/H39.md"

cat > "$BODYDIR/H40.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H40` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H40 — The rate limiter fails hard-closed with an untyped 500 when Redis is unavailable

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 3 days |
| **Dependencies** | H41 gives the typed envelope. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `security/RateLimitFilter.java:107-109`
- `config/RateLimitConfig.java:33-36` — the application does not boot without Redis

**Files in scope:** `security/RateLimitFilter.java`, `config/RateLimitConfig.java`

**Root cause**

The failure covers `/auth/token`, which is the only way a partner gets a token. The filter sits above the exception handler, so the partner receives a Spring default error body that its SDK cannot parse. A Redis outage therefore becomes a total partner outage with an unreadable error.

**Implementation spec**

1. Decide the behaviour per endpoint. Fail open with a local in-memory counter on `/auth/token` and on reads. Fail closed on money-moving writes.
2. Return the platform error envelope with a typed code, for example `RATE_LIMIT_BACKEND_UNAVAILABLE`. Give the filter its own error writer, because it runs above the handler.
3. Let the application boot without Redis in a degraded mode, and report the degradation in the health endpoint.
4. Alert on the degraded mode. Fail-open without an alert is a silent control failure.

**Do NOT do this** — considered and rejected

> **Fail open everywhere so a Redis outage never blocks a partner**
> Fail-open on a money-moving write removes the only brake during the exact incident when a retry storm is most likely.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H40" "[H40] The rate limiter fails hard-closed with an untyped 500 when Redis is unavailable" "ready-for-agent" "$BODYDIR/H40.md"

cat > "$BODYDIR/H41.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H41` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H41 — Three fused error envelopes, one of them scraped from Mambu's documentation

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 1 week |
| **Dependencies** | Do it before the partner pilot; changing an error contract afterwards breaks integrations. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `common/api/ApiError.java:45-58` — errorCode, errorReason and errorSource come from Mambu; the scraped pages are still in docs/API-references/
- `About 93 error codes exist as bare string literals with no enum`
- `The generated OpenAPI declares only HTTP 200 and no error schema`

**Files in scope:** `common/api/ApiError.java`, `docs/API-references/`

**Root cause**

A partner cannot write one error handler against this contract. An untyped code set also drifts silently: a typo in a literal is a new error code that no client knows.

**Implementation spec**

1. Define one envelope and write it in the partner contract. Version the API surface if a change breaks an existing integration.
2. Replace the string literals with an `ErrorCode` enum, and make the enum the only source. A test then enumerates the whole catalogue.
3. Declare the error responses in the OpenAPI document, so the generated client has typed errors.
4. Delete the scraped Mambu pages from `docs/API-references/`. Keeping a competitor's copied documentation in the repo is a legal exposure and it misleads the next author.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H41" "[H41] Three fused error envelopes, one of them scraped from Mambu's documentation" "ready-for-agent" "$BODYDIR/H41.md"

cat > "$BODYDIR/H42.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H42` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H42 — A 90-day plaintext PII dossier sits in the idempotency store

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-7 — PII |
| **Effort** | 4 days |
| **Dependencies** | H22 registry, §7.5 erasure |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `domain/LspApiIdempotencyRecord.java:41-42` — response_body
- `web/LspLoanApplicationResponses.java:28-86` — name, date of birth, unmasked PAN, address, employer, income

**Files in scope:** `domain/LspApiIdempotencyRecord.java`, `web/LspLoanApplicationResponses.java`

**Root cause**

Aadhaar and the account number are masked in the same object, so the discipline exists and was not extended to PAN. A DPDP erasure that deletes the borrower leaves this copy behind, which defeats the erasure.

**Implementation spec**

1. Store a redacted response body. Apply the same field classification registry as H22, so one rule governs every surface.
2. Where the exact bytes must be replayable, store the response hash plus the identifiers, and rebuild the response on replay from live data.
3. Shorten the retention window and pin it deliberately. Adyen's 7 days suits a bank-reconciliation system better than 90 days.
4. Include the idempotency store in the erasure path (§7.5). Any store that holds borrower data must appear in that path.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H42" "[H42] A 90-day plaintext PII dossier sits in the idempotency store" "ready-for-agent" "$BODYDIR/H42.md"

cat > "$BODYDIR/H43.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H43` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H43 — No backup, restore or disaster recovery exists in the repository

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-9 — Operations |
| **Effort** | 1 week including the first drill |
| **Dependencies** | Needs the production hosting decision. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `infra/docker-compose.yml:2-17` — a bare postgres:17-alpine with no WAL archiving
- `docs/deployment-strategy.md §8 states RPO under 5 minutes and RTO under 1 hour in the present tense`

**Files in scope:** `infra/docker-compose.yml`, `docs/deployment-strategy.md`

**Root cause**

The migration runbook's first step is to restore into an isolated database. That step cannot be followed. The stated recovery objectives are not measured and not tested, and a document that states an untested number in the present tense is worse than a document that says nothing.

**Implementation spec**

1. Turn on WAL archiving and point-in-time recovery on the managed database. Record the retention window.
2. Run a restore drill and record the measured RPO and RTO. Repeat the drill each quarter and record the date.
3. Correct `deployment-strategy.md` to state the measured numbers and the date of the last drill.
4. Add the restore procedure to the runbook with concrete commands, so it can be followed under pressure.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Boot and deployment safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H43" "[H43] No backup, restore or disaster recovery exists in the repository" "ready-for-agent" "$BODYDIR/H43.md"

cat > "$BODYDIR/H44.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H44` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H44 — An OpsAlert never leaves the database

| | |
|---|---|
| **Status** | DEFERRED by the owner — do not start |
| **Severity** | High |
| **Workstream** | WS-9 — Observability |
| **Effort** | 1 week |
| **Dependencies** | C12 for the metric side. Approve both together. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `needs-triage` |

**Evidence — read these first**

- `service/OpsAlertService.java:59-92` — the method ends at save()
- `The team's own documents call this 'detection without delivery'`

**Files in scope:** `service/OpsAlertService.java`

**Root cause**

A CRITICAL DPD-90 alert has the same effect as no alert. Nothing alerts on an intent stuck in REQUESTED or UNKNOWN, on worker liveness, on Redis being down, or on a webhook backlog. The detection logic is good and nobody receives it.

**Implementation spec**

1. Add a delivery dispatcher behind the existing outbox pattern: alert row, then outbox row, then delivery. Reuse the webhook outbox machinery instead of writing a second one.
2. Route by severity: CRITICAL pages, HIGH goes to email and chat, the rest stays in the dashboard.
3. De-duplicate. One condition that persists for six hours must not produce 720 messages. Group by rule and target, with a re-notify interval.
4. Add the four missing conditions: intent stuck, worker liveness, Redis unavailable, and webhook backlog depth.
5. Record the delivery outcome, so an undelivered alert is itself an alert.

**Owner decision (recorded)** — Deferred

Deferred with C12 on 2026-08-02. Alert delivery waits until the observability stack decision is made.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H44" "[H44] An OpsAlert never leaves the database" "needs-triage" "$BODYDIR/H44.md"

cat > "$BODYDIR/H45.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H45` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H45 — The correlation id is written to MDC and never rendered

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-9 — Observability |
| **Effort** | 1 week |
| **Dependencies** | H46 changes the worker threading; do them together. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `common/correlation/CorrelationIdFilter.java:36`
- `common/correlation/CorrelationIdHolder.java:5` — a plain ThreadLocal that does not cross into worker threads
- `No logback-spring.xml exists`

**Files in scope:** `common/correlation/CorrelationIdFilter.java`, `common/correlation/CorrelationIdHolder.java`, `spring.xml`

**Root cause**

The console pattern has no MDC token, so the id is never printed. The holder does not propagate into worker threads, so every worker-written audit row carries a null correlation id. There are 52 log statements across 411 files, on stdout, with no retention. CERT-In requires 180 rolling days in India.

**Implementation spec**

1. Add `logback-spring.xml` with a JSON encoder that includes the MDC. Structured logs are the requirement, not a preference, when the logs must be searched under a deadline.
2. Propagate the context into worker threads with a `TaskDecorator` on the scheduler and on the webhook executor.
3. Ship logs to a store inside India with 180-day retention, and record the region in the ADR.
4. Raise the log density on the money paths. 52 statements across 411 files is too few to reconstruct an incident.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Observability". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H45" "[H45] The correlation id is written to MDC and never rendered" "ready-for-agent" "$BODYDIR/H45.md"

cat > "$BODYDIR/H46.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H46` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H46 — All 7 scheduled workers share one thread

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-9 — Workers |
| **Effort** | 4 days |
| **Dependencies** | Pairs with H44 and H45. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `LmsApplication.java:12` — no TaskScheduler bean and no pool size property, so Boot's default pool is 1
- `service/LoanDisbursementWorkerService.java:110-119` — findByStatus is unbounded and has no per-item try/catch

**Files in scope:** `service/LoanDisbursementWorkerService.java`

**Root cause**

A slow bank status pass occupies the single thread for minutes. Alerting, reporting and webhook dispatch simply do not run in that window. One application that throws also aborts the whole batch.

**Implementation spec**

1. Give the money workers their own scheduler and keep the rest on a shared pool. A shared pool with a larger size still lets a slow worker delay a critical one.
2. Bound every worker query with a page size, and process in pages.
3. Add a per-item try/catch. One bad row must not stop the batch. Count the failures and alert above a threshold.
4. Add a liveness metric per worker: the last successful run time. Alert when a worker has not completed inside its expected interval. This is the missing signal in H44.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Observability". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H46" "[H46] All 7 scheduled workers share one thread" "ready-for-agent" "$BODYDIR/H46.md"

cat > "$BODYDIR/H47.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `H47` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### H47 — No deployment artifact, no prod profile, no graceful shutdown

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-9 — Deployment |
| **Effort** | 1 week |
| **Dependencies** | C11 for the profile work. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `infra/ and application*.yml` — no Dockerfile, no manifests, no prod profile, no server.shutdown: graceful
- `backend/pom.xml:22-25` — spring-boot-starter-amqp with zero Java references
- `Two Hikari pools bind the same property block, so outside local each defaults to 10`

**Files in scope:** `backend/pom.xml`

**Root cause**

SIGTERM drops in-flight requests and interrupts the scheduler mid-batch, which for this system means mid-disbursement. The two pools silently take 20 connections per instance. An unused AMQP starter also adds attack surface and start-up cost for nothing.

**Implementation spec**

1. Add a layered Dockerfile and the deployment manifests. Pin the base image by digest.
2. Add a `prod` profile that fails fast when a required secret is absent, and that pairs with C11.
3. Set `server.shutdown: graceful` with a timeout above the longest request. Make the workers check an interrupt flag between items.
4. Give each Hikari pool its own property block and set the sizes deliberately. Document the total connection budget per instance against the database maximum.
5. Remove `spring-boot-starter-amqp`.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Boot and deployment safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "H47" "[H47] No deployment artifact, no prod profile, no graceful shutdown" "ready-for-agent" "$BODYDIR/H47.md"

cat > "$BODYDIR/M1.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M1` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M1 — Money crosses the wire as a JSON number

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-7 — Contract |
| **Effort** | 1 week |
| **Dependencies** | Do it with H41, which is the other contract change. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `BigDecimal to JSON number to TypeScript number`
- `frontend/src/schemas/lsp-provided-schedule.ts:99` — a constant named FLOAT_NOISE

**Files in scope:** `frontend/src/schemas/lsp-provided-schedule.ts`

**Root cause**

RFC 8259 guarantees interoperability only inside ±2^53, and `NUMERIC(19,2)` exceeds that. The symptom is already in the repository: a constant exists whose only job is to absorb floating-point error. Stripe sends integer minor units. Google sends decimal strings.

**Implementation spec**

1. Send money as a decimal string on every API surface. Add a Jackson serialiser for the money type and apply it through the type, not field by field.
2. Parse into a decimal type on the frontend, and format for display. Never let a rupee value become a JavaScript number.
3. Delete `FLOAT_NOISE`. A tolerance constant that hides a representation defect must not survive the fix.
4. This changes the partner contract, so do it before the pilot or version the surface.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M1" "[M1] Money crosses the wire as a JSON number" "ready-for-agent" "$BODYDIR/M1.md"

cat > "$BODYDIR/M2.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M2` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M2 — Row-level security policies are written in a shape that cannot be optimised

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-1 — Tenancy / scale |
| **Effort** | 1 week |
| **Dependencies** | C13 for a realistic test. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `V41:148-175` — app_current_lsp_id() is STABLE and not PARALLEL SAFE; policies use it bare
- `tenant_owns_application() is a correlated EXISTS executed for each row`
- `idx_loan_application_status and idx_loan_application_created_at do not lead with lsp_id`


**Root cause**

A bare function call in a policy cannot be hoisted into a one-shot InitPlan, so Postgres re-evaluates it for each row. The correlated EXISTS is worse. A published case measured 178,000 ms falling to 12 ms after the same fix. The defect is invisible at the present data size and becomes an outage at scale.

**Implementation spec**

1. Wrap the call in a subselect: `USING (lsp_id = (SELECT app_current_lsp_id()))`. This is the documented pattern and it produces the InitPlan.
2. Mark the helper functions `PARALLEL SAFE`. Add `LEAKPROOF` only after a review, because it is a security assertion and it needs a superuser.
3. Replace `tenant_owns_application()` with a denormalised `lsp_id` column on the child tables, maintained by the insert path. A direct column comparison is the shape that scales; a correlated subquery is not.
4. Lead the tenant composite indexes with `lsp_id`, and create them with CONCURRENTLY (M4).
5. Add a performance test with a realistic row count, so the next policy change cannot regress this silently.

**Scale note**

This is the single largest scale risk in the tenancy design. Fix it in the same quarter as C1, not later.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Tenancy completion". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M2" "[M2] Row-level security policies are written in a shape that cannot be optimised" "ready-for-agent" "$BODYDIR/M2.md"

cat > "$BODYDIR/M3.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M3` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M3 — autoCommit=false on the tenant pool combines badly with the Postgres outbox

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-9 — Database |
| **Effort** | 2 days |
| **Dependencies** | C12 for the metrics |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `tenant/TenantAwareDataSource` — autoCommit false is required for SET LOCAL semantics


**Root cause**

The setting is correct and necessary for RLS. It also increases the number of open transactions, which pins the MVCC horizon and stops the outbox table from vacuuming. The mitigation is cheap and the failure is expensive.

**Implementation spec**

1. Set `idle_in_transaction_session_timeout` and `statement_timeout` on the tenant role.
2. Alert on `age(backend_xmin)` and on the oldest transaction age.
3. Tune autovacuum on the outbox table specifically: a lower scale factor, because it is a high-churn table.
4. Add the dead-tuple count for the outbox to the metrics from C12.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Observability". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M3" "[M3] autoCommit=false on the tenant pool combines badly with the Postgres outbox" "ready-for-agent" "$BODYDIR/M3.md"

cat > "$BODYDIR/M4.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M4` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M4 — No CREATE INDEX CONCURRENTLY in 103 index creations

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-9 — Database |
| **Effort** | 2 days for the lint and the pattern |
| **Dependencies** | None. Do it before the first production index. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `103 index creations across 113 migrations, all blocking`


**Root cause**

Every index build blocks writes on the table. On an empty database this is invisible. On a live book each migration becomes a write outage for the duration of the build.

**Implementation spec**

1. Use `CREATE INDEX CONCURRENTLY` for every index on a table that already exists in production. It needs a non-transactional migration, so mark those migrations accordingly in Flyway.
2. Add a migration lint test: fail the build on a `CREATE INDEX` without CONCURRENTLY when the target table is created in an earlier migration.
3. Add the runbook step for a failed concurrent build, because it leaves an invalid index that must be dropped and rebuilt.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Boot and deployment safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M4" "[M4] No CREATE INDEX CONCURRENTLY in 103 index creations" "ready-for-agent" "$BODYDIR/M4.md"

cat > "$BODYDIR/M5.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M5` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M5 — Status columns are bare VARCHAR with no CHECK constraint

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-9 — Database |
| **Effort** | 3 days |
| **Dependencies** | C13 |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `One CHECK across 113 migrations, and it is a jsonb_typeof guard`
- `V65's header documents the omission as deliberate, so the enum vocabulary can evolve`


**Root cause**

The trade is defensible and it was written down, which is good practice. The consequence is that the database has no bar to an impossible value. A defect or a manual repair can write `CLOSED` where the state machine forbids it, and nothing rejects it.

**Implementation spec**

1. Keep VARCHAR and add a generated CHECK constraint per status column.
2. Generate the constraint from the Java enum in a test, and fail the build when the database constraint and the enum differ. The vocabulary can then still evolve, and it evolves in both places at once.
3. Do not use a Postgres enum type. Adding a value to a Postgres enum is awkward and the team's stated reason for avoiding it is correct.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Boot and deployment safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M5" "[M5] Status columns are bare VARCHAR with no CHECK constraint" "ready-for-agent" "$BODYDIR/M5.md"

cat > "$BODYDIR/M6.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M6` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M6 — Unmasked PAN inside ops_alert.message

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-6 — PII |
| **Effort** | 2 days |
| **Dependencies** | H22 registry |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `service/OpsAlertEmitters.java:200-205` — string concatenation into the message

**Files in scope:** `service/OpsAlertEmitters.java`

**Root cause**

The PAN reaches the operations surface outside the PII-reveal audit. `PanMasking` exists two packages away and is not used here.

**Implementation spec**

1. Never concatenate PII into an alert message. The alert already has a structured context field — put identifiers there, masked.
2. Render the message at read time from the structured context, with the same masking rules as every other surface (H22).
3. Add a test that scans emitted alert messages for PAN and account-number patterns.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M6" "[M6] Unmasked PAN inside ops_alert.message" "ready-for-agent" "$BODYDIR/M6.md"

cat > "$BODYDIR/M7.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M7` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M7 — PAN is unmasked on every JSON surface and masked only in the CSV

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-6 — PII |
| **Effort** | Included in H22 |
| **Dependencies** | H22 |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `PanMasking has exactly one production call site`
- `Raw PAN goes out on the borrower list, the ops list and detail, and the LSP surface` — on the line after AadhaarMasking.mask(...)


**Root cause**

The masking discipline exists and was applied to one identifier. PAN is the identifier this platform uses as the borrower key, so it appears on nearly every response.

**Implementation spec**

1. Apply the field classification registry from H22 at the serialisation layer, so masking is the default and an unmasked value needs an explicit, audited reveal.
2. Mask PAN on the LSP surface too. The partner sourced the customer and does not need the full PAN back on every read.
3. Add a response-scanning test across the API surface, which catches the next unmasked field.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M7" "[M7] PAN is unmasked on every JSON surface and masked only in the CSV" "ready-for-agent" "$BODYDIR/M7.md"

cat > "$BODYDIR/M8.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M8` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M8 — CSV formula injection

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-9 — Reporting |
| **Effort** | 1 day |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `RFC-4180 quoting only, with no neutralisation of a leading = + - @`
- `Borrower name and organisation arrive from LSP intake`
- `\r is missing from the quoting predicate`


**Root cause**

A borrower name that starts with an equals sign becomes a formula when an operator opens the file in a spreadsheet. The input comes from a partner, so it is not trusted.

**Implementation spec**

1. Prefix any cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return with a single quote, and quote the cell.
2. Add `\r` to the quoting predicate.
3. Add a test with each dangerous prefix.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Reporting correctness". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M8" "[M8] CSV formula injection" "ready-for-agent" "$BODYDIR/M8.md"

cat > "$BODYDIR/M9.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M9` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M9 — The filesystem storage provider is reachable in production

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-6 — Storage |
| **Effort** | 1 day |
| **Dependencies** | C11's profile work |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Defaults to java.io.tmpdir; setProvider(null) falls back to LOCAL`
- `The read path resolves an arbitrary storageKey with no normalize or containment check`


**Root cause**

The upload side closes path traversal correctly. The read side does not. A local provider in production also writes KYC documents to a temporary directory that a restart clears.

**Implementation spec**

1. Refuse to boot outside the local profile when the provider is LOCAL. Remove the null-to-LOCAL fallback.
2. Add `normalize()` and a containment check on the read path, matching the upload path.
3. Add a traversal test on the read path.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Boot and deployment safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M9" "[M9] The filesystem storage provider is reachable in production" "ready-for-agent" "$BODYDIR/M9.md"

cat > "$BODYDIR/M10.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M10` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M10 — Idempotency-Key is required on 3 endpoints, optional on 4, and absent on 4

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 3 days |
| **Dependencies** | H39 unifies the implementation. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `PATCH /borrowers/{id}/bank-details has none, and it changes where money goes`
- `The payload-size filter covers only POST and PUT, so it skips that endpoint too`


**Root cause**

A partner cannot learn one rule. The endpoint that changes the payout beneficiary is the one with no idempotency and no size limit.

**Implementation spec**

1. Require the header on every state-changing endpoint, with no exception.
2. Extend the payload-size filter to PATCH and DELETE.
3. State the rule once in the partner contract and enforce it with a test that enumerates the mutating endpoints.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M10" "[M10] Idempotency-Key is required on 3 endpoints, optional on 4, and absent on 4" "ready-for-agent" "$BODYDIR/M10.md"

cat > "$BODYDIR/M11.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M11` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M11 — Offset pagination with an unbounded offset, and three endpoints with none

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 4 days |
| **Dependencies** | H35 caps the size; do them together. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `The partner list endpoint uses offset pagination with no ceiling`
- `A correct keyset implementation already exists for the audit explorer`


**Root cause**

A deep offset makes Postgres scan and discard every earlier row. The correct pattern is already in this codebase, and the audit calls that implementation correct.

**Implementation spec**

1. Move the partner list endpoints to keyset pagination, and reuse the audit explorer's implementation rather than writing a second one.
2. Add pagination to the three endpoints that have none.
3. Cap the page size at the server.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M11" "[M11] Offset pagination with an unbounded offset, and three endpoints with none" "ready-for-agent" "$BODYDIR/M11.md"

cat > "$BODYDIR/M12.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M12` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M12 — Raw exception messages are returned to partners on 4xx

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-7 — Partner API |
| **Effort** | 2 days |
| **Dependencies** | H41 |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Includes Hibernate's 'Unable to find com.bhawana.lms.domain.LoanApplication with id ...'`
- `5xx is correctly scrubbed`


**Root cause**

The message leaks internal class names and the persistence technology. It also gives an enumeration oracle: a different message for a wrong id and for another tenant's id.

**Implementation spec**

1. Scrub 4xx the same way 5xx is scrubbed. Map known exceptions to typed codes from the H41 enum.
2. Return the same message for 'not found' and 'not yours'. Log the difference; do not return it.
3. Add a test that asserts no response body contains a fully-qualified class name.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Partner API surface". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M12" "[M12] Raw exception messages are returned to partners on 4xx" "ready-for-agent" "$BODYDIR/M12.md"

cat > "$BODYDIR/M13.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M13` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M13 — The frontend has no field-level error mapping

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-8 — Operations UI |
| **Effort** | 4 days |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `The backend sends violations[] with a field on each`
- `readResponseError never reads them; form.setError has zero call sites`


**Root cause**

The backend does the work and the frontend discards it. Every typed 409 and 422 code is unhandled, so an operator sees a generic failure instead of the field that is wrong.

**Implementation spec**

1. Parse `violations[]` in `readResponseError`.
2. Add one shared helper, `applyServerErrors(form, violations)`, and call it from every mutation error handler.
3. Map the typed business codes to operator-readable messages in one place, so the wording is consistent.
4. Add a test for each of the codes that operators meet most often.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Operator double-submit safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M13" "[M13] The frontend has no field-level error mapping" "ready-for-agent" "$BODYDIR/M13.md"

cat > "$BODYDIR/M14.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M14` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M14 — Five of six list screens fetch the whole table and paginate in JavaScript

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-8 — Operations UI |
| **Effort** | 1 week |
| **Dependencies** | M11 provides the endpoints. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Every alert on the platform, message text included, transits to the browser`


**Root cause**

This works at the present data size and stops working without warning. Alert message text also contains unmasked PAN today (M6), so the whole alert table lands in the browser query cache.

**Implementation spec**

1. Move the five screens to server-side pagination, and use the keyset endpoints from M11.
2. Keep the previous page visible during a fetch, so the operator does not see a flash of empty state.
3. Add a guard test that fails when a list query has no page parameter.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Operator double-submit safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M14" "[M14] Five of six list screens fetch the whole table and paginate in JavaScript" "ready-for-agent" "$BODYDIR/M14.md"

cat > "$BODYDIR/M15.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M15` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M15 — A failed read after a successful write reports the write as failed

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-8 — Operations UI |
| **Effort** | 2 days |
| **Dependencies** | Do it with C9 and C10. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Promise.all([POST, fetchChecklist]) rejects when the GET fails`


**Root cause**

The operator sees a failure for an action that succeeded, and clicks again. With C9's fresh key on each click, that is the double-disbursement path.

**Implementation spec**

1. Never combine a write and a read in one `Promise.all`. Await the write, then invalidate the query cache and let the read refetch.
2. When the refetch fails, show the write as successful and the view as stale. Those are different facts and the operator needs both.
3. Add a test that fails the follow-up read and asserts the success state.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Operator double-submit safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M15" "[M15] A failed read after a successful write reports the write as failed" "ready-for-agent" "$BODYDIR/M15.md"

cat > "$BODYDIR/M16.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M16` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M16 — The repayment posted date is truncated in UTC

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-8 — Operations UI |
| **Effort** | 2 days |
| **Dependencies** | H12 for the backend half |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `new Date(v).toISOString() then .slice(0, indexOf("T"))`


**Root cause**

A payment at 03:00 IST on 1 August is posted as 31 July. `@PastOrPresent` is satisfied, so nothing errors and the wrong date reaches the ledger. This is the frontend half of H12.

**Implementation spec**

1. Format the date from the local date parts, or use a timezone-aware formatter with Asia/Kolkata. Never use `toISOString().slice(0, 10)` for a business date.
2. Add an ESLint rule that bans that expression, with the same ratchet approach as C9.
3. Send the business date as a date string, not as an instant, on every business-date field.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Operator double-submit safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M16" "[M16] The repayment posted date is truncated in UTC" "ready-for-agent" "$BODYDIR/M16.md"

cat > "$BODYDIR/M17.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M17` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M17 — Foreclosure execute is a bare button with no confirmation

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-8 — Operations UI |
| **Effort** | 3 days |
| **Dependencies** | Do it with C9 and C10. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `components/app/foreclosure/* has zero non-test importers, and so do the disbursement dialogs and ConfirmDestructiveDialog`


**Root cause**

The confirmation dialogs were built and never wired. A single click executes a settlement. The components exist, so this is a wiring defect and not a design gap.

**Implementation spec**

1. Wire the existing dialogs to the foreclosure execute, the disbursement initiate and every destructive action.
2. Require the operator to type the loan reference for the highest-risk actions, in the pattern the destructive dialog already implements.
3. Add a bundle check that fails when a component under `components/app/` has zero non-test importers. That check finds the next orphan.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Operator double-submit safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M17" "[M17] Foreclosure execute is a bare button with no confirmation" "ready-for-agent" "$BODYDIR/M17.md"

cat > "$BODYDIR/M18.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `M18` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### M18 — Roles are collapsed to one, with a fail-open default to OPS_USER

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Medium |
| **Workstream** | WS-8 — Access control |
| **Effort** | 4 days |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Backend users hold a Set; selectPrimaryRole returns a single value`
- `An unknown role defaults to OPS_USER, which has cross-tenant read and loan write`


**Root cause**

The default is the defect. An unrecognised role must grant nothing. Today a new backend role that the frontend does not know becomes an operations user in the interface.

**Implementation spec**

1. Carry the full role set through the frontend session model, and derive each capability from the set.
2. Remove the default. An unknown role grants no capability and shows an explicit error.
3. Generate the role and capability list from the backend contract, so the two cannot drift.
4. This is a display-layer control. The backend already enforces authority, and that must stay true — see C10.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Operator double-submit safety". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "M18" "[M18] Roles are collapsed to one, with a fail-open default to OPS_USER" "ready-for-agent" "$BODYDIR/M18.md"

cat > "$BODYDIR/L1.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `L1` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### L1 — app_permission and app_role_permission are dead schema

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Low |
| **Workstream** | WS-9 — Schema |
| **Effort** | Half a day |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Present in V1, referenced by zero Java`


**Root cause**

Dead schema reads as capability. A reviewer or an auditor assumes a permission model exists.

**Implementation spec**

1. Drop both tables in a migration, unless a fine-grained permission model is planned this year.
2. If it is planned, add a comment on the tables that names the plan and the date.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Housekeeping". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "L1" "[L1] app_permission and app_role_permission are dead schema" "ready-for-agent" "$BODYDIR/L1.md"

cat > "$BODYDIR/L2.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `L2` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### L2 — loan_application_pii_reveal_audit is migrated, protected, modelled and permanently empty

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Low |
| **Workstream** | WS-6 — Schema |
| **Effort** | 2 days |
| **Dependencies** | H23 |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `No writer exists`


**Root cause**

An empty audit table reads as coverage. An investigator who queries it sees no reveals and concludes none happened.

**Implementation spec**

1. Wire the writer on the application PII reveal path, in the same shape as the borrower reveal audit.
2. If the reveal path does not exist, drop the table and add it back with the feature.
3. This also appears in H23 — the explorer does not cover it either.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Evidence and audit integrity". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "L2" "[L2] loan_application_pii_reveal_audit is migrated, protected, modelled and permanently empty" "ready-for-agent" "$BODYDIR/L2.md"

cat > "$BODYDIR/L3.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `L3` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### L3 — allInstallmentsSettled is vacuously true for an empty schedule

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Low |
| **Workstream** | WS-3 — Correctness |
| **Effort** | 1 hour |
| **Dependencies** | None |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Stream.allMatch on an empty list returns true`


**Root cause**

It is unreachable today because other code guards it. The predicate itself is wrong, and the guard is somewhere else.

**Implementation spec**

1. Return false for an empty schedule. A loan with no schedule is not settled.
2. Add the test for the empty case.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Money core mechanics". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "L3" "[L3] allInstallmentsSettled is vacuously true for an empty schedule" "ready-for-agent" "$BODYDIR/L3.md"

cat > "$BODYDIR/R3.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `R3` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### R3 — Bureau reporting — fortnightly, with a ₹100-a-day compensation meter

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Regulatory |
| **Workstream** | WS-10 — Bureau reporting |
| **Effort** | 4 weeks |
| **Dependencies** | Hard dependency on C6, H13 and H14. It cannot start before them. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Zero hits for bureau, cibil, experian, equifax or crif`


**Root cause**

Since 1 January 2025 a credit institution must report to the CICs fortnightly, as on the 15th and the last day, submitted inside 7 calendar days of the fortnight close. A nodal officer must be appointed for CIC grievances, and a change notified inside 5 calendar days. On a dispute the credit institution has 21 days and the CIC has 9. Beyond the 30 days the borrower is entitled to ₹100 a day.

**Implementation spec**

1. Build in this order: day-end classification (H13, H14), then durable per-loan classification rows, then the fortnightly extract, then the submission, then the dispute intake.
2. Put a `ci_due_at` column on the dispute row, so the compensation meter is visible before it starts to run, and not after.
3. Record the nodal officer as data, with the notification obligation as a task, because a change must be notified inside 5 days.
4. Add a submission audit with the file hash and the acknowledgement, so a missed fortnight is visible.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Regulatory obligations". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "R3" "[R3] Bureau reporting — fortnightly, with a ₹100-a-day compensation meter" "ready-for-agent" "$BODYDIR/R3.md"

cat > "$BODYDIR/R4.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `R4` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### R4 — Sanctions, PEP and STR screening — the obligation with immediate action

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Regulatory |
| **Workstream** | WS-10 — Screening |
| **Effort** | 4 weeks |
| **Dependencies** | No technical dependency. This can start immediately, which is why the audit ranks it early. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `Zero hits for sanction, pep, watchlist, screen, riskCategor or reKyc`


**Root cause**

Under UAPA section 51A a match against the UNSC consolidated lists requires an immediate freeze, with no court order, and a report to the designated UAPA officer and to FIU-IND. The existing book must be re-screened on every list update. This carries the sharpest tail risk on the whole list: every other finding produces a regulatory finding, and disbursing to a listed person is a different category of event.

**Implementation spec**

1. Build `sanctions_list_version` and `screening_result`, with a match score, a disposition and maker-checker fields.
2. Add `risk_category`, `is_pep` and `next_rekyc_due_at` to the borrower. Risk categorisation drives re-KYC at 10, 8 and 2 years for low, medium and high.
3. Gate at onboarding and again before disbursement. The list can change between the two.
4. Re-screen the book on every list ingest.
5. Use fuzzy name matching, because transliteration variance in Indian names is large. That implies a potential-match queue with human disposition — and it is the first genuine use of the maker-checker framework (H27).
6. Add PEP identification with senior-management approval and enhanced due diligence, and an STR path to FIU-IND. CTR is likely moot, because there is no cash rail.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Regulatory obligations". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "R4" "[R4] Sanctions, PEP and STR screening — the obligation with immediate action" "ready-for-agent" "$BODYDIR/R4.md"

cat > "$BODYDIR/R5.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `R5` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### R5 — Retention and erasure — neither capability exists

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | Regulatory |
| **Workstream** | WS-10 — Retention and erasure |
| **Effort** | 6 weeks |
| **Dependencies** | H19, H20, H21 and H26 are its parts. Order them as listed. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `One retention worker, for idempotency records at 90 days`
- `RefreshTokenRepository.deleteByExpiresAtBefore exists and nothing calls it`
- `Zero storage deletes; no consent entity; no erasure endpoint; 15 audit tables, none partitioned`


**Root cause**

Four obligations pull in two directions. PMLA section 12 needs 5 years. CERT-In needs 180 rolling days in India. MCA Rule 3(1) needs an audit trail that cannot be disabled. DPDP needs erasure when the purpose is served, with 48 hours of notice before a scheduled erasure. DPDP section 8(5) settles the conflict: a legal retention requirement prevails, and Bhawana must state the specific basis and delete everything the mandate does not cover. "We keep it all because PMLA" is not a defence.

**Implementation spec**

1. Build `data_retention_policy`: data class, legal basis, period and disposition. Every table maps to a class. A table with no class fails a test.
2. Build `erasure_request` with the assessment, the `notify_at` timestamp and a tombstone record.
3. Change every audit foreign key to ON DELETE RESTRICT before any erasure code exists (H20). The erasure path would otherwise destroy the evidence, because `borrower_pii_reveal_audit` cascades from `borrower`.
4. Restrict the tenant role to SELECT and INSERT on audit tables (H21).
5. Turn on R2 Object Lock in compliance mode, with separate write-only and read-only credentials (H26).
6. Add a lifecycle rule that expires generated MIS files at 30 to 90 days. Those files contain the whole book.
7. Partition the audit tables by month. The team projects 50 to 150 million rows a year, so partitioning must exist before the volume does.
8. Call `deleteByExpiresAtBefore`. It is written and unused.

**Scale note**

Partitioning is the item that must not be late. Retrofitting a partition scheme onto a 100-million-row audit table on a live system is a multi-day operation with an outage.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Regulatory obligations". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "R5" "[R5] Retention and erasure — neither capability exists" "ready-for-agent" "$BODYDIR/R5.md"

cat > "$BODYDIR/N1.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `N1` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### N1 — A full 12 MB Formance Go repository sits inside the Java source tree, untracked

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | New |
| **Workstream** | WS-9 — Repository hygiene |
| **Effort** | 10 minutes |
| **Dependencies** | None. Related to open question 1 — the ledger decision. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `backend/src/main/java/com/bhawana/lms/formance/` — 727 Go files, 0 Java files, 12 MB
- `It holds its own go.mod, Dockerfile, CODEOWNERS, LICENSE and CLAUDE.md`
- `git check-ignore reports it is NOT ignored, and git status shows it as untracked`

**Files in scope:** `backend/src/main/java/com/bhawana/lms/formance/`

**Root cause**

This is not in the audit report. I found it in this pass. Someone cloned the Formance ledger to study the ledger design — which is a reasonable thing to do while question 1 is open. The clone landed inside `src/main/java`. It is not ignored, so one `git add -A` commits 12 MB of another project's source, with its own LICENSE and CODEOWNERS, into this repository.

**Implementation spec**

1. Move it out of the repository. Reference material belongs outside the source tree, or in a documented, ignored scratch directory.
2. Add the path to `.gitignore` as a second guard.
3. If the Formance comparison is useful, record the conclusions in an ADR and delete the clone. A 12 MB clone is not a durable record; a written comparison is.
4. Check the build: a non-Java directory under `src/main/java` is usually harmless for Maven, and it slows every IDE index and every file search in this repository.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Housekeeping". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "N1" "[N1] A full 12 MB Formance Go repository sits inside the Java source tree, untracked" "ready-for-agent" "$BODYDIR/N1.md"

cat > "$BODYDIR/N2.md" <<'LMSEOF'
> Auto-created from the consolidated audit of 2026-07-31.
> Tracker: `outputs/bhawana-consolidated-audit-2026-07-31/CONSOLIDATED-AUDIT-TECHNICAL-TRACKER.md`
> Source finding: `N2` in `CONSOLIDATED-AUDIT.md` (baseline `bfd571f`)


### N2 — The tenant connection strategy is selected by matching a hostname string, and Azure will not match it

| | |
|---|---|
| **Status** | APPROVED — ready to build |
| **Severity** | High |
| **Workstream** | WS-1 — Tenancy / portability |
| **Effort** | 3 days |
| **Dependencies** | Do it with C1, which is already approved and touches the same class. |
| **GitHub issue** | _not created yet_ |
| **Triage label** | `ready-for-agent` |

**Evidence — read these first**

- `tenant/TenantIsolationDataSourceConfig.java:39` — boolean supabasePooler = jdbcUrl.contains("pooler.supabase.com")
- `tenant/TenantIsolationDataSourceConfig.java:44-48` — that flag decides which credentials the tenant pool uses
- `tenant/TenantIsolationDataSourceConfig.java:72-79` — and whether TenantAwareDataSource issues SET ROLE
- `V96__grant_set_role_on_tenant_app.sql` — exists only to make the SET ROLE path work

**Files in scope:** `tenant/TenantIsolationDataSourceConfig.java`, `V96__grant_set_role_on_tenant_app.sql`

**Root cause**

A security-relevant decision is taken by a substring match on a URL. Two different identity strategies exist: connect directly as `lms_tenant_app`, or connect as the admin login and then `SET ROLE`. Today the choice between them is made by whether the JDBC URL happens to contain `pooler.supabase.com`. On Azure that substring never appears, so the code silently takes the direct-login path. If the Azure role setup does not permit a direct login as the tenant role, the tenant pool fails. Tenant isolation should rest on a stated deployment setting rather than on the shape of a connection URL.

**Implementation spec**

1. Replace the hostname match with explicit configuration: `app.tenant.connection-strategy: DIRECT_LOGIN | ASSUME_ROLE`. The deployment states its own strategy and the code stops guessing.
2. Fail fast at boot. Assert at startup that the tenant connection actually holds the tenant role identity, and refuse to start when it does not. A silent fallback to the admin identity is the failure mode that matters, because it is C1 all over again.
3. If the ASSUME_ROLE strategy is used behind PgBouncer in transaction mode, issue `SET ROLE` **inside the transaction**, not once on connect. A pooled connection is handed to a different client between transactions, so a connect-time role does not survive. The existing `set_config(..., true)` call is already transaction-scoped and correct — the role must follow the same rule.
4. Add an integration test for each strategy, so both paths are exercised rather than only the one the current environment picks.
5. Keep V96, and make its comment name the strategy rather than the vendor.

**Do NOT do this** — considered and rejected

> **Add an Azure hostname to the same condition when the move happens**
> It repeats the defect with a second vendor string and it fails the same way on the third. The weakness lies in deciding identity by pattern-matching a URL, so adding more patterns leaves it in place.

**Scale note**

Azure recommends PgBouncer in transaction mode, so the transaction-scoped rule above is the likely production configuration rather than an edge case.

**Owner decision (recorded)** — Approved

Approved on 2026-08-01 in the batch "Tenancy completion". Build it as specified above. Raise a question before deviating from the spec, because several items in this batch share one design object.

**Definition of done**

- Every numbered step above is implemented.
- A test exists that fails against the pre-fix code and passes after. For anything touching tenancy, the schema, or money, that test runs on Testcontainers Postgres with Flyway (see C13).
- `mvn -pl backend test` passes; for frontend items `npm run verify` passes in `frontend/`.
- If the change alters an architectural decision, an ADR is added under `docs/adr/`.
- If the change adds a migration, it is numbered from `V114` upward and follows M4 (index creation) and M5 (status CHECK) where applicable.

---

LMSEOF
create_issue "N2" "[N2] The tenant connection strategy is selected by matching a hostname string, and Azure will not match it" "ready-for-agent" "$BODYDIR/N2.md"

echo
echo "Done. Issue map written to $MAP"
echo "Now backfill the tracker with the issue links:"
echo "  node scripts/backfill-issue-links.mjs"
