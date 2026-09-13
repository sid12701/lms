# Backend

Spring Boot service for the LMS platform.

## Prerequisites

- Java 21
- Local infra from `infra/docker-compose.yml`

Maven is provided via the wrapper (`mvnw` / `mvnw.cmd`) — no system Maven required.

## Run locally

1. Set secrets in the **repo-root** `.env` (see `backend/.env.example` for variable names). Gitignored — do not commit.

2. Start the API from `backend/` (explicitly activate `local` to load **repo-root** `.env` via `application-local.yml` and allow simulation):

Booting **without** an explicitly active profile fails closed (no implicit local default): production safety checks require strong secrets, secure cookies, wired storage and rotated tenant credentials unless every active profile is `local`/`test`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local        # macOS/Linux/Git Bash
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local      # Windows PowerShell/cmd
```

Rate limiting is **off** by default in `local` (`app.rate-limit.enabled=false`) so the API starts without Redis. Set `APP_RATE_LIMIT_ENABLED=true` in the repo-root `.env` when Redis is up (`docker compose -f infra/docker-compose.yml up -d redis`).

Mail defaults to localhost unless overridden in the root `.env`. PostgreSQL comes from `LMS_DB_URL`, `LMS_DB_USERNAME`, and `LMS_DB_PASSWORD` in the **repo-root** `.env`.

### Supabase (remote PostgreSQL)

Use the **Session pooler** connection string from the Supabase dashboard (Connect → JDBC / URI). Do **not** use the direct host `db.<project-ref>.supabase.co` on Windows unless IPv6 DNS works end-to-end — it is often IPv6-only and Java fails with `UnknownHostException`.

```properties
LMS_DB_URL=jdbc:postgresql://aws-1-<region>.pooler.supabase.com:5432/postgres?sslmode=require
LMS_DB_USERNAME=postgres.<project-ref>
LMS_DB_PASSWORD=<database-password>
```

Port `5432` = session mode (required for Flyway). Port `6543` is transaction mode only.

The tenant connection pool auto-detects `pooler.supabase.com` URLs: it authenticates with the pooler user above, then runs `SET ROLE` to `APP_TENANT_DATASOURCE_USERNAME` (default `lms_tenant_app`). On PostgreSQL 16+ that `SET ROLE` requires an explicit `WITH SET TRUE` membership grant; migration `V96` applies it automatically. Restart the backend after changing database settings.

The session pooler caps clients at ~15. Both Hikari pools honor `spring.datasource.hikari.*` from `application-local.yml` (max 5 / min idle 2 each), keeping the combined maximum under the cap so Flyway and external scripts can still connect.

Optional: copy `src/main/resources/application-local.yml.example` if you need to customize non-secret local settings.

## Tests

From `backend/`:

```bash
./mvnw test          # macOS/Linux/Git Bash
mvnw.cmd test        # Windows
```

Compile test sources only: `mvnw.cmd test-compile`.

### Database safety (default `mvn test`)

Routine `mvn test` is safe when a repo-root `.env` points at a shared database:

1. **`IntegrationTestDatabaseTargetGuard`** — `IntegrationTestDatabaseCleaner` refuses to bulk-delete against any JDBC URL that is not in-memory H2 or a datasource explicitly marked `lms.it.ephemeral-database=true` (Testcontainers). There is **no** environment-variable opt-in: no test run can be pointed at a real database.
2. **Testcontainers by default** — upload regression runs on ephemeral PostgreSQL (`DocumentUploadPostgresIntegrationTest`), not `@ActiveProfiles("local")`.

If you need to reproduce a bug against a real database, do it by hand against a **copy** — never by pointing the integration-test cleaner at it.

### Integration-test tenant scope

`TenantContextTestExecutionListener` (`src/test/java/com/bhawana/lms/support/`) applies admin datasource scope before each `@SpringBootTest` method so fixture setup can write across tenants. Tests that assert missing context opt out with `@RequiresEmptyTenantContext`. Registered in `src/test/resources/META-INF/spring.factories` and via `@TestExecutionListeners` on many controller tests; the `@LmsSpringBootTest` stereotype bundles the same listeners.

### IDE troubleshooting

The Maven reactor is rooted at the repo `pom.xml` (`lms` aggregator → `backend` module). If the editor reports unresolved imports for classes under `com.bhawana.lms.support` while `mvnw.cmd test-compile` succeeds, reload the Java language server (**Java: Clean Java Language Server Workspace** → Reload). Repo-wide editor settings live in `.vscode/settings.json`.

### Disbursement intent workflow (S3 / MNY-01, C04: only path)

Disbursement initiation always goes through the durable intent path:

1. **Request** — `POST …/disbursement-requests` commits a `disbursement_intent` row before any bank call.
2. **Execute** — `LoanDisbursementWorker` claims intents with `SKIP LOCKED` and calls the provider outside a transaction.
3. **Outcome** — request log, intent state, and a loan event are written in a short follow-up transaction.

Ops money preview (Spec S12):

- `GET /api/v1/internal/ops/loan-applications/{id}/disbursement-preview` — principal, fee, net, payment mode, masked beneficiary (`beneficiarySource=LIVE_BORROWER` until Spec S5).
- `GET /api/v1/internal/ops/loan-applications/{id}/disbursement-reference` — durable `tranRefNo` from live intent (after Tx-A) or request log.

Integration tests run the same intent path as production. C04 removed the legacy inline path and its `enabled` flag.

G01 simulation guard: the mock adapter, mock-outcome route, and worker auto-resolve only run under an explicit simulation profile (`test`, `local`, `dev`). Boot locally with `local` active, and set `APP_DISBURSEMENT_WORKER_AUTO_RESOLVE_MOCK=false` in production.

Full record: `docs/implementation-log.md`.

### Partner repayment schedule validation (S20 / SCH-01)

`PUT /api/v1/lsp/loan-applications/{id}/repayment-schedule` with `mode: LSP_PROVIDED` always enforces principal integrity **and** date/interest discipline under `app.schedule.validation.*` (product-accepted defaults; checks always on).

| Bound | Default |
|---|---|
| First due after approval | 1–60 days |
| Cadence drift vs `firstDue + i months` | ±7 days |
| Horizon grace beyond tenure | 75 days |
| Interest row / total tolerance | max(₹10, 2%) / max(₹100, 1%) |

Partner contract and violation codes: `docs/partner-schedule-validation.md`. Platform `mode: GENERATED` schedules self-validate.

## Bootstrap auth

The service exposes `POST /api/v1/auth/login` for local user sign-in and `POST /api/v1/auth/token` for API client token issuance.

### Machine access tokens (H20)

`POST /api/v1/auth/token` returns an **access token only** — no refresh cookie. Machine clients reacquire access through client credentials (or, when G02 is explicitly enabled and provisioned, Entra app tokens). Access TTL is `app.security.jwt.ttl` (default 30 minutes). Secret rotation bumps `token_version` and invalidates outstanding access tokens immediately; the previous secret remains valid only for the configured grace window on the **token** endpoint, not via refresh. Legacy API-client refresh rows are rejected and revoked (migration V127).

### Entra machine identity (G02, disabled by default)

`app.security.entra-machine-identity.enabled` defaults to `false`. Enabling requires real tenant/API/client identifiers, trusted JWKS URI, assigned app role and signed-off mappings to enabled local `api_client` rows — see `application.yml` comments. Full partner cutover is a separate staged step.

The authentication converter attaches the verified local client/LSP identity to each request; external `lspId` and internal-role claims never select tenant or IP-allowlist scope. Setting a mapping's `revoked-at` rejects all its tokens until explicitly cleared. `not-before` is a separate minimum token issuance time. Client deactivation/revocation also invalidates older external credentials.

JWKS connect/read timeouts must be positive whole-millisecond values. The defaults are 2 seconds each; failed retrievals consume the configured `unknown-kid-min-interval` retry budget. Expired keys are not used through an outage. Real provisioning must supply an approved secure JWKS endpoint; token-supplied key URLs are ignored.

### Human session cutover and recovery

Human tokens require the configured human audience and a live session family. Legacy tokens/refresh rows without the required family metadata force a new login. Replacing the local signing key, issuer or audiences also changes the refresh policy epoch and forces reauthentication; there is no key-overlap compatibility window. Logout revokes one family; password reset/change, disablement and role/LSP reassignment revoke the intended user's sessions.

Human credential failures are counted immediately under a database row lock. The failure window uses the configured auth brute-force threshold/window; successful login and an explicit administrator password reset clear it. Account-status or IP-policy rejections do not consume credential failures. The scheduler is secondary.

The browser requires Web Locks and functioning coordination storage. Cookie exchanges remain blocked after an uncertain response or a peer disappearing mid-exchange. Recovery requires closing all app tabs and establishing a clean browser context/site state. Reloading or automatically deleting the in-flight marker is not safe recovery.


Local credentials are defined in the repo-root `.env`:

- `APP_SECURITY_BOOTSTRAP_USERNAME` (default in `backend/.env.example`: `ops.admin`)
- `APP_SECURITY_BOOTSTRAP_EMAIL` (set explicitly outside development profiles)
- `APP_SECURITY_BOOTSTRAP_PASSWORD` (single password input — do not use deprecated `APP_SECURITY_BOOTSTRAP_LOGIN_PASSWORD`)

On-demand bootstrap recovery: `POST /api/v1/internal/system/bootstrap-sync` requires an active SYSTEM_ADMIN and creates a missing bootstrap user. Routine startup/sync preserves an existing password, roles and status; use the explicit audited administrator reset workflow to replace credentials.

## Production / staging

Use a non-`local` Spring profile (for example `prod`). Startup **fails** if bootstrap password or JWT secret are missing, too short, or still set to development placeholders.
Do not commit the repo-root `.env` or real credentials in YAML.
