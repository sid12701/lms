# Backend Documentation

## 1. Backend Overview

The backend is a Java Spring Boot service located under `backend/`. It uses Maven, Java 21, Spring Boot 3.5.11, Spring MVC, Spring Security, Spring Data JPA, Flyway, PostgreSQL, Redis-backed rate limiting, scheduled workers, and optional S3-compatible document storage.

| Area | Code-supported finding |
|---|---|
| Runtime/framework | Java 21, Spring Boot, Maven |
| Main entry point | `backend/src/main/java/com/bhawana/lms/LmsApplication.java` |
| API base path | Most application APIs use `/api/v1` |
| Persistence | PostgreSQL through Spring Data JPA and Flyway SQL migrations |
| Main schema source | JPA entities in `domain/` plus Flyway migrations in `resources/db/migration/` |
| Authentication | Spring Security stateless JWT resource server, username/password login, API client credentials, refresh token cookie |
| Authorization | URL rules in `SecurityConfig`, method-level `@PreAuthorize`, roles in `AppRoleCode`, LSP tenant context and PostgreSQL RLS |
| Main modules | Auth, admin metadata/users/API clients/LSPs/products, LSP loan APIs, internal loan ops, reports, alerts, webhooks, document storage |
| Integrations | PostgreSQL, Redis, SMTP, outbound webhooks, optional R2/S3-compatible object storage |
| Background jobs | Scheduled webhook dispatcher, scheduled report request processor, application bootstrappers/seeders |
| API docs | OpenAPI/Springdoc is exposed through `/v3/api-docs/**` and Swagger UI paths when enabled by Springdoc |

The backend also contains generated Graphify output folders under `backend/graphify-out` and `backend/src/main/java/com/bhawana/graphify-out`. These appear to be generated analysis artifacts, not runtime backend modules. Needs review if generated files should live inside `src/main/java`.

## 2. Backend Folder Structure

```txt
backend/
  pom.xml
  README.md
  src/
    main/
      java/com/bhawana/lms/
        LmsApplication.java
        common/
          api/
          correlation/
          web/
        config/
        domain/
        repo/
        security/
        service/
        tenant/
        web/
      resources/
        application.yml
        application-local.yml
        db/migration/
    test/
      java/com/bhawana/lms/
  graphify-out/
```

| Folder | Purpose |
|---|---|
| `backend/src/main/java/com/bhawana/lms` | Main backend Java package |
| `common/api` | Standard API envelope, error shape, and pagination response helper |
| `common/correlation` | Correlation ID filter and request tracing support |
| `common/web` | Global exception handling |
| `config` | OpenAPI, Jackson, tenant web configuration, and infrastructure beans |
| `domain` | JPA entities and enums for users, roles, LSPs, products, borrowers, loans, reports, webhooks, alerts, tokens, and documents |
| `repo` | Spring Data repositories and custom query implementations |
| `security` | Spring Security, JWT, rate limiting, IP allowlist, SSRF-safe URL validation, and security properties |
| `service` | Business services, lifecycle workflows, storage providers, reports, webhooks, and bootstrapping |
| `tenant` | Tenant-aware data source, tenant context, and PostgreSQL RLS context handling |
| `web` | REST controllers, request/response DTO records, LSP tenant interceptor, and controller helpers |
| `resources/db/migration` | Flyway SQL migrations that define and evolve the PostgreSQL schema |
| `resources/application.yml` | Main backend configuration and environment variable bindings |
| `resources/application-local.yml` | Local profile configuration. Contains sensitive-looking defaults and should be reviewed before committing or deploying |
| `src/test` | Backend tests, including auth, lifecycle, tenant isolation, reports, webhooks, and controller tests |

## 3. Backend Files Inventory

| File path | Category | Responsibility | Key exports/functions/classes | Connected modules/files |
|---|---|---|---|---|
| `backend/pom.xml` | Build | Maven dependencies, Java version, Spring Boot plugin | Maven project config | All backend modules |
| `backend/src/main/java/com/bhawana/lms/LmsApplication.java` | Entry point | Starts Spring Boot app, enables config properties, scheduling, entity scan, JPA repositories | `LmsApplication` | `domain`, `repo`, `service` scheduled workers |
| `backend/src/main/resources/application.yml` | Config | Datasource, Flyway, JPA, Redis, RabbitMQ, mail, JWT, reports, webhooks, storage, rate limits | Spring properties | Security, services, integrations |
| `backend/src/main/resources/application-local.yml` | Config | Local profile defaults for database, mail, JWT, bootstrap user, seed data | Spring local properties | Local development |
| `backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java` | Security | Main security filter chain, URL authorization, JWT decoder, CORS, password encoder | `SecurityConfig` | Controllers, JWT auth, `AppUserDetailsService` |
| `backend/src/main/java/com/bhawana/lms/security/SecurityProperties.java` | Security config | Binds app security settings, bootstrap credentials, JWT cookie settings | `SecurityProperties` | `SecurityConfig`, auth services |
| `backend/src/main/java/com/bhawana/lms/security/AppUserDetailsService.java` | Security | Loads managed users and bootstrap user for Spring Security | `AppUserDetailsService` | `AppUserRepository`, `SecurityProperties` |
| `backend/src/main/java/com/bhawana/lms/security/JwtTokenService.java` | Security | Issues and validates access tokens, refresh tokens, password version claims | `JwtTokenService` | `RefreshTokenRepository`, `AppUserRepository` |
| `backend/src/main/java/com/bhawana/lms/security/RateLimitFilter.java` | Middleware | Applies Redis/Bucket4j limits to auth and LSP write calls | `RateLimitFilter` | Redis, `RateLimitConfig`, `LspAuthenticationSupport` |
| `backend/src/main/java/com/bhawana/lms/security/LspIpAllowlistFilter.java` | Middleware | Enforces per-LSP CIDR allowlist for LSP API requests when entries exist | `LspIpAllowlistFilter` | `LspIpAllowlistRepository`, JWT claims |
| `backend/src/main/java/com/bhawana/lms/security/SsrfSafeUrlValidator.java` | Security | Validates outbound webhook URLs against private/reserved hosts | `SsrfSafeUrlValidator` | `HttpWebhookDeliveryClient` |
| `backend/src/main/java/com/bhawana/lms/common/correlation/CorrelationIdFilter.java` | Middleware | Propagates `X-Correlation-Id` and MDC logging context | `CorrelationIdFilter` | Controllers, services, error responses |
| `backend/src/main/java/com/bhawana/lms/common/web/GlobalExceptionHandler.java` | Error handling | Converts exceptions into `ApiError` responses | `GlobalExceptionHandler` | All controllers |
| `backend/src/main/java/com/bhawana/lms/common/api/ApiError.java` | API shape | Standard error body | `ApiError`, `Violation` | `GlobalExceptionHandler`, auth entry points |
| `backend/src/main/java/com/bhawana/lms/common/api/ApiEnvelope.java` | API shape | Standard success envelope for selected responses | `ApiEnvelope` | Controllers |
| `backend/src/main/java/com/bhawana/lms/common/api/PaginationResponseBuilder.java` | API shape | Builds paginated or non-paginated list envelopes | `PaginationResponseBuilder` | List endpoints |
| `backend/src/main/java/com/bhawana/lms/tenant/TenantAwareDataSource.java` | Tenant isolation | Sets PostgreSQL session context for current LSP before tenant queries | `TenantAwareDataSource` | RLS migrations, repositories |
| `backend/src/main/java/com/bhawana/lms/tenant/TenantRoutingDataSource.java` | Tenant isolation | Routes between admin and tenant-aware datasource modes | `TenantRoutingDataSource` | `TenantIsolationDataSourceConfig` |
| `backend/src/main/java/com/bhawana/lms/tenant/TenantDataAccessContextHolder.java` | Tenant isolation | Holds current tenant and access mode in thread context | `TenantDataAccessContextHolder` | LSP interceptor, tenant datasource |
| `backend/src/main/java/com/bhawana/lms/config/TenantIsolationDataSourceConfig.java` | Tenant isolation | Creates admin and tenant-aware datasource beans | `TenantIsolationDataSourceConfig` | JPA, RLS |
| `backend/src/main/java/com/bhawana/lms/web/AuthController.java` | Controller | Login, API client token, refresh, password change, logout | `AuthController` | Security services, refresh token repository |
| `backend/src/main/java/com/bhawana/lms/web/SystemController.java` | Controller | Returns current authenticated context | `SystemController` | JWT claims, roles |
| `backend/src/main/java/com/bhawana/lms/web/AdminMetadataController.java` | Controller | Admin lookup metadata | `AdminMetadataController` | `AdminMetadataService` |
| `backend/src/main/java/com/bhawana/lms/web/UserAdminController.java` | Controller | Admin user list/create/reset password | `UserAdminController` | `AdminDirectoryService` |
| `backend/src/main/java/com/bhawana/lms/web/ApiClientAdminController.java` | Controller | API client list/create | `ApiClientAdminController` | `ApiClientManagementService` |
| `backend/src/main/java/com/bhawana/lms/web/LspAdminController.java` | Controller | LSP CRUD-like admin operations and webhook subscription | `LspAdminController` | LSP repositories/services |
| `backend/src/main/java/com/bhawana/lms/web/LspIpAllowlistAdminController.java` | Controller | LSP CIDR allowlist management | `LspIpAllowlistAdminController` | `LspIpAllowlistRepository` |
| `backend/src/main/java/com/bhawana/lms/web/LoanProductAdminController.java` | Controller | Product list/detail/create/update/mapping/audit | `LoanProductAdminController` | `ProductConfigurationService` |
| `backend/src/main/java/com/bhawana/lms/web/ProductLspMappingAdminController.java` | Controller | Product-to-LSP mapping management | `ProductLspMappingAdminController` | `ProductConfigurationService` |
| `backend/src/main/java/com/bhawana/lms/web/BorrowerAdminController.java` | Controller | Internal borrower detail lookup | `BorrowerAdminController` | Borrower repositories |
| `backend/src/main/java/com/bhawana/lms/web/HomeDashboardController.java` | Controller | Internal dashboard overview metrics | `HomeDashboardController` | `HomeDashboardService` |
| `backend/src/main/java/com/bhawana/lms/web/LoanApplicationOpsController.java` | Controller | Internal ops loan workflows | `LoanApplicationOpsController` | Loan lifecycle, documents, payments, disbursement |
| `backend/src/main/java/com/bhawana/lms/web/LspProductApiController.java` | Controller | LSP product catalog | `LspProductApiController` | `LspProductCatalogService` |
| `backend/src/main/java/com/bhawana/lms/web/LspLoanApplicationApiController.java` | Controller | LSP loan application API | `LspLoanApplicationApiController` | Loan application/lifecycle/document services |
| `backend/src/main/java/com/bhawana/lms/web/LspLoanApiController.java` | Controller | LSP loan account servicing API | `LspLoanApiController` | Loan account, repayment, foreclosure services |
| `backend/src/main/java/com/bhawana/lms/web/LspOptionsController.java` | Controller | Internal LSP option lookups | `LspOptionsController` | LSP repository |
| `backend/src/main/java/com/bhawana/lms/web/ReportAdminController.java` | Controller | Portfolio MIS preview, export, async report requests | `ReportAdminController` | `AdminReportingService`, `ReportRequestService` |
| `backend/src/main/java/com/bhawana/lms/web/OpsAlertController.java` | Controller | Ops alert list and acknowledge | `OpsAlertController` | `OpsAlertService` |
| `backend/src/main/java/com/bhawana/lms/web/WebhookOutboxAdminController.java` | Controller | Webhook outbox list and manual dispatch | `WebhookOutboxAdminController` | `WebhookOutboxService` |
| `backend/src/main/java/com/bhawana/lms/web/LspTenantContextInterceptor.java` | Middleware | Extracts LSP claim and sets tenant context for `/api/v1/lsp/**` | `LspTenantContextInterceptor` | Tenant data source, LSP controllers |
| `backend/src/main/java/com/bhawana/lms/web/LspAuthenticationSupport.java` | Controller helper | Reads authenticated LSP/API client context from JWT | `LspAuthenticationSupport` | LSP controllers, filters |
| `backend/src/main/java/com/bhawana/lms/service/LoanApplicationService.java` | Service | Loan application creation, validation, duplicate checks, intake audit | `LoanApplicationService` | Repositories, lifecycle, products, borrowers |
| `backend/src/main/java/com/bhawana/lms/service/LoanApplicationLifecycleService.java` | Service | Status transitions, approval, assignment, document checklist, account creation | `LoanApplicationLifecycleService` | Loan repos, webhook outbox |
| `backend/src/main/java/com/bhawana/lms/service/LoanApprovalService.java` | Service | Approval-specific rules and account creation support | `LoanApprovalService` | Loan application/account repos |
| `backend/src/main/java/com/bhawana/lms/service/LoanDisbursementService.java` | Service | Disbursement requests and mock outcome handling | `LoanDisbursementService` | `loan_disbursement_request_log`, webhooks |
| `backend/src/main/java/com/bhawana/lms/service/LoanDocumentService.java` | Service | Document metadata, storage, access audit, ZIP/content retrieval | `LoanDocumentService` | Storage services, document checklist |
| `backend/src/main/java/com/bhawana/lms/service/LoanRepaymentScheduleService.java` | Service | Generate/upsert repayment schedules | `LoanRepaymentScheduleService` | Repayment schedule repository |
| `backend/src/main/java/com/bhawana/lms/service/LoanRepaymentCommandService.java` | Service | Payment recording and allocation | `LoanRepaymentCommandService` | Payment transactions, installments |
| `backend/src/main/java/com/bhawana/lms/service/LoanForeclosureCommandService.java` | Service | Foreclosure quote and execution flow | `LoanForeclosureCommandService` | Foreclosure quotes, loan accounts |
| `backend/src/main/java/com/bhawana/lms/service/LspApiIdempotencyService.java` | Service | Idempotency record lookup and replay for LSP mutations | `LspApiIdempotencyService` | `lsp_api_idempotency_record` |
| `backend/src/main/java/com/bhawana/lms/service/LspProductCatalogService.java` | Service | LSP-scoped product catalog response | `LspProductCatalogService` | Product mapping repositories |
| `backend/src/main/java/com/bhawana/lms/service/ProductConfigurationService.java` | Service | Product and LSP mapping management, product audit events | `ProductConfigurationService` | Product repositories |
| `backend/src/main/java/com/bhawana/lms/service/ApiClientManagementService.java` | Service | Creates API clients and one-time secrets | `ApiClientManagementService` | API client repo, password encoder |
| `backend/src/main/java/com/bhawana/lms/service/ApiClientAuthenticationService.java` | Service | Validates API client credentials and updates last-used timestamp | `ApiClientAuthenticationService` | API client repo |
| `backend/src/main/java/com/bhawana/lms/service/AdminDirectoryService.java` | Service | Admin user management and temporary password reset | `AdminDirectoryService` | User/role/LSP repos |
| `backend/src/main/java/com/bhawana/lms/service/AdminMetadataService.java` | Service | Admin metadata payloads | `AdminMetadataService` | Role, status, product, LSP data |
| `backend/src/main/java/com/bhawana/lms/service/AdminReportingService.java` | Service | Portfolio MIS query/export | `AdminReportingService` | Report repositories |
| `backend/src/main/java/com/bhawana/lms/service/ReportRequestService.java` | Service | Async report request lifecycle | `ReportRequestService` | `report_request` |
| `backend/src/main/java/com/bhawana/lms/service/ReportRequestProcessingWorker.java` | Worker | Scheduled async report processing | `ReportRequestProcessingWorker` | `AdminReportingService`, `ReportNotificationService` |
| `backend/src/main/java/com/bhawana/lms/service/ReportNotificationService.java` | Integration | Sends report-ready/failure emails | `ReportNotificationService` | `JavaMailSender` |
| `backend/src/main/java/com/bhawana/lms/service/WebhookOutboxService.java` | Service | Enqueues and dispatches webhook events | `WebhookOutboxService` | Webhook repositories, delivery client |
| `backend/src/main/java/com/bhawana/lms/service/WebhookOutboxDispatchWorker.java` | Worker | Scheduled webhook delivery | `WebhookOutboxDispatchWorker` | `WebhookOutboxService` |
| `backend/src/main/java/com/bhawana/lms/service/HttpWebhookDeliveryClient.java` | Integration | Sends signed outbound webhook POST requests | `HttpWebhookDeliveryClient` | `RestClient`, `SsrfSafeUrlValidator` |
| `backend/src/main/java/com/bhawana/lms/service/DocumentStorageService.java` | Storage | Storage abstraction for loan documents | `DocumentStorageService` | Local/R2 storage implementations |
| `backend/src/main/java/com/bhawana/lms/service/FileSystemLoanDocumentStorageService.java` | Storage | Local filesystem document storage | `FileSystemLoanDocumentStorageService` | Document service |
| `backend/src/main/java/com/bhawana/lms/service/R2LoanDocumentStorageService.java` | Storage | S3-compatible R2 document storage | `R2LoanDocumentStorageService` | AWS S3 SDK |
| `backend/src/main/java/com/bhawana/lms/service/RoleBootstrapService.java` | Bootstrap | Seeds configured role codes | `RoleBootstrapService` | Role repository |
| `backend/src/main/java/com/bhawana/lms/service/LocalBootstrapAdminSyncService.java` | Bootstrap | Syncs local bootstrap admin user | `LocalBootstrapAdminSyncService` | Security config, user/role repos |
| `backend/src/main/java/com/bhawana/lms/service/SampleCatalogSeedService.java` | Bootstrap | Optional sample LSP/product seed data | `SampleCatalogSeedService` | Product/LSP repositories |
| `backend/src/main/java/com/bhawana/lms/service/LocalDemoPortfolioSeedService.java` | Bootstrap | Optional local demo portfolio seed data | `LocalDemoPortfolioSeedService` | Loan and borrower repositories |
| `backend/src/main/java/com/bhawana/lms/domain/*` | Domain | JPA entities and enums | Entity classes | Repositories, services, migrations |
| `backend/src/main/java/com/bhawana/lms/repo/*` | Data access | Spring Data repositories and custom query code | Repository interfaces/classes | Services, controllers |
| `backend/src/main/resources/db/migration/V*.sql` | Database | Schema, indexes, constraints, RLS, seed data, hardening | Flyway migrations | Domain/repository layer |

## 4. Endpoint Map by Role and Access Level

Access comes from a combination of `SecurityConfig`, controller `@PreAuthorize`, route interceptors, and service-level checks. Any `/api/v1/**` route not explicitly permitted requires an authenticated JWT and must not have `ROLE_PASSWORD_CHANGE_REQUIRED`.

### Public / Permit All

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| GET | `/actuator/health/**` | Health checks | Public | `SecurityConfig.permitAll` | Spring Actuator | None | Actuator health JSON | Needs review | Needs review | Exposed by security config |
| GET | `/actuator/info` | App info | Public | `SecurityConfig.permitAll` | Spring Actuator | None | Actuator info JSON | Needs review | Needs review | Exposed by security config |
| GET | `/swagger-ui.html` | Swagger UI | Public | `SecurityConfig.permitAll` | Springdoc | None | HTML | None | None | Public API docs surface |
| GET | `/swagger-ui/**` | Swagger UI assets | Public | `SecurityConfig.permitAll` | Springdoc | None | Static assets | None | None | Public API docs surface |
| GET | `/v3/api-docs/**` | OpenAPI spec | Public | `SecurityConfig.permitAll` | Springdoc | None | OpenAPI JSON | None | None | Public API docs surface |
| POST | `/api/v1/auth/login` | Managed user login | Public | `SecurityConfig.permitAll`, auth rate limit | `AuthController.login` | `username`, `password` | `TokenResponse` plus refresh cookie | `app_user`, `app_user_role`, `app_role`, `refresh_token` | Redis if rate limit enabled | Issues access token and refresh cookie |
| POST | `/api/v1/auth/token` | LSP API client credentials token | Public | `SecurityConfig.permitAll`, auth rate limit | `AuthController.token` | `clientId`, `clientSecret` | `TokenResponse` plus refresh cookie | `api_client`, `lsp`, `refresh_token` | Redis if rate limit enabled | Client secret checked with bcrypt |
| POST | `/api/v1/auth/refresh` | Rotate refresh token and issue new access token | Public | `SecurityConfig.permitAll` | `AuthController.refresh` | Refresh cookie `lms-refresh` | `TokenResponse` plus rotated refresh cookie | `refresh_token`, `app_user` or `api_client` context | None | Cookie-based endpoint with CSRF considerations |
| POST | `/api/v1/auth/logout` | Revoke refresh token and clear cookie | Public | `SecurityConfig.permitAll` | `AuthController.logout` | Refresh cookie if present | Empty/no-content-style response | `refresh_token` | None | Public so clients can clear cookie even if access token expired |

### Authenticated User

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| POST | `/api/v1/auth/password` | Change temporary/required password | Authenticated managed user | URL requires authenticated; allowed while password change is required | `AuthController.changePassword` | `newPassword` with min length 12 | `TokenResponse` plus rotated refresh cookie | `app_user`, `refresh_token` | None | Requires `passwordChangeRequired`; rejects same temporary password |
| GET | `/api/v1/internal/system/context` | Current principal, roles, auth type, LSP context | `SYSTEM_ADMIN`, `OPS_USER`, `PRODUCT_ADMIN`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize` | `SystemController.context` | None | Principal/context DTO | JWT claims; may read user/LSP context | None | Allows LSP UI roles despite `/internal` path |

### System Admin

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| GET | `/api/v1/internal/admin/metadata` | Admin lookup metadata | `SYSTEM_ADMIN` | `@PreAuthorize` | `AdminMetadataController.getMetadata` | None | Metadata DTO/list envelope | Roles, LSPs, products, statuses | None | Admin-only |
| GET | `/api/v1/internal/admin/api-clients` | List API clients | `SYSTEM_ADMIN` | `@PreAuthorize` | `ApiClientAdminController.list` | Optional list params | List/envelope | `api_client`, `lsp` | None | Does not expose client secret |
| POST | `/api/v1/internal/admin/api-clients` | Create API client | `SYSTEM_ADMIN` | `@PreAuthorize`, `@Valid` | `ApiClientAdminController.create` | `name`, `description`, `lspId`, optional `status` | Created client plus one-time `clientSecret` | `api_client`, `lsp` | None | Secret is only returned once and should be handled as sensitive |
| GET | `/api/v1/internal/admin/lsps` | List LSPs | `SYSTEM_ADMIN` | `@PreAuthorize` | `LspAdminController.list` | Optional filters | List/envelope | `lsp` | None | Admin-only |
| GET | `/api/v1/internal/admin/lsps/{lspId}` | LSP detail | `SYSTEM_ADMIN` | `@PreAuthorize` | `LspAdminController.get` | Path `lspId` | LSP detail | `lsp` | None | May include webhook subscription fields |
| POST | `/api/v1/internal/admin/lsps` | Create LSP | `SYSTEM_ADMIN` | `@PreAuthorize`, `@Valid` | `LspAdminController.create` | `code`, `name`, `status` | LSP response | `lsp` | None | Code uniqueness enforced |
| PUT | `/api/v1/internal/admin/lsps/{lspId}/webhook-subscription` | Configure webhook subscription | `SYSTEM_ADMIN` | `@PreAuthorize`, `@Valid` | `LspAdminController.updateWebhookSubscription` | `enabled`, `endpointUrl`, `signingSecret`, `eventTypes` | LSP webhook response | `lsp` | None | Signing secret is sensitive and appears in admin response |
| GET | `/api/v1/internal/admin/lsps/{lspId}/ip-allowlist` | List LSP IP allowlist entries | `SYSTEM_ADMIN` | `@PreAuthorize` | `LspIpAllowlistAdminController.list` | Path `lspId` | List | `lsp_ip_allowlist`, `lsp` | None | Empty allowlist means allow all for that LSP |
| POST | `/api/v1/internal/admin/lsps/{lspId}/ip-allowlist` | Add CIDR allowlist entry | `SYSTEM_ADMIN` | `@PreAuthorize`, `@Valid` | `LspIpAllowlistAdminController.create` | `cidr`, `description` | Created entry | `lsp_ip_allowlist`, `lsp` | None | CIDR validation should be reviewed in code path |
| DELETE | `/api/v1/internal/admin/lsps/{lspId}/ip-allowlist/{entryId}` | Delete allowlist entry | `SYSTEM_ADMIN` | `@PreAuthorize` | `LspIpAllowlistAdminController.delete` | Path ids | Empty/no-content-style response | `lsp_ip_allowlist` | None | Removing all entries disables allowlist enforcement for that LSP |
| GET | `/api/v1/internal/admin/users` | List users | `SYSTEM_ADMIN` | `@PreAuthorize` | `UserAdminController.list` | Optional filters | List/envelope | `app_user`, `app_role`, `lsp` | None | Admin-only user directory |
| POST | `/api/v1/internal/admin/users` | Create user | `SYSTEM_ADMIN` | `@PreAuthorize`, `@Valid` | `UserAdminController.create` | `username`, `email`, `password`, `status`, `lspId`, `roles` | User response | `app_user`, `app_user_role`, `app_role`, `lsp` | None | Password stored as bcrypt hash |
| POST | `/api/v1/internal/admin/users/{userId}/reset-password` | Reset user password | `SYSTEM_ADMIN` | `@PreAuthorize` | `UserAdminController.resetPassword` | Path `userId` | Temporary password response | `app_user` | None | Temporary password is sensitive and returned to admin |
| GET | `/api/v1/internal/home/overview` | Dashboard overview | `SYSTEM_ADMIN` | `@PreAuthorize` | `HomeDashboardController.overview` | Optional query params | Metrics DTO | Loan, LSP, product, report tables | None | Admin dashboard data |
| GET | `/api/v1/internal/reports/portfolio-mis/preview` | Preview MIS rows | `SYSTEM_ADMIN` | `@PreAuthorize` | `ReportAdminController.previewPortfolioMis` | Optional `lspId`, date filters, pagination | List/envelope | Loan account/application/product/LSP/borrower | None | Read-heavy report endpoint |
| GET | `/api/v1/internal/reports/portfolio-mis/summary` | MIS summary | `SYSTEM_ADMIN` | `@PreAuthorize` | `ReportAdminController.portfolioMisSummary` | Optional `lspId`, date filters | Summary DTO | Loan account/application/product/LSP/borrower | None | Admin-only |
| GET | `/api/v1/internal/reports/portfolio-mis` | Download MIS CSV | `SYSTEM_ADMIN` | `@PreAuthorize` | `ReportAdminController.downloadPortfolioMis` | Optional `lspId`, date filters | `text/csv` | Loan account/application/product/LSP/borrower | None | Exports borrower/loan data |
| POST | `/api/v1/internal/reports/portfolio-mis/requests` | Create async MIS report request | `SYSTEM_ADMIN` | `@PreAuthorize`, `@Valid` | `ReportAdminController.createPortfolioMisRequest` | `lspId`, date filters, `recipientEmail` | Report request DTO | `report_request`, LSP, loan data later | SMTP later if enabled | Email address and report contents are sensitive |
| GET | `/api/v1/internal/reports/requests` | List report requests | `SYSTEM_ADMIN` | `@PreAuthorize` | `ReportAdminController.listRequests` | Optional filters/pagination | List/envelope | `report_request` | None | Admin-only |
| GET | `/api/v1/internal/reports/requests/{requestId}/download` | Download completed report | `SYSTEM_ADMIN` | `@PreAuthorize` | `ReportAdminController.downloadRequest` | Path `requestId` | CSV/content response | `report_request` | None | Report content stored in DB |
| GET | `/api/v1/internal/admin/webhook-outbox` | List webhook outbox events | `SYSTEM_ADMIN` | `@PreAuthorize` | `WebhookOutboxAdminController.list` | Optional filters/pagination | List/envelope | `webhook_event_outbox`, `lsp` | None | Includes delivery state and errors |
| POST | `/api/v1/internal/admin/webhook-outbox/dispatch` | Manually dispatch pending webhooks | `SYSTEM_ADMIN` | `@PreAuthorize` | `WebhookOutboxAdminController.dispatch` | Optional batch controls | Dispatch summary | Webhook outbox and attempts | Outbound webhooks | Can send external HTTP requests |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes` | List foreclosure quotes | `SYSTEM_ADMIN` | Method `@PreAuthorize` | `LoanApplicationOpsController.listForeclosureQuotes` | Path id | List | `loan_foreclosure_quote`, `loan_account` | None | Admin-only servicing view |
| POST | `/api/v1/internal/ops/loan-applications/{applicationId}/manual-status` | Force manual loan status | `SYSTEM_ADMIN` | Method `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.manualStatus` | `targetStatus`, `note`, `reasonCode` | Loan application response | `loan_application`, audit/status tables | Webhook outbox | Security-sensitive manual state change |
| POST | `/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests` | Create disbursement request | `SYSTEM_ADMIN` | Method `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.createDisbursementRequest` | Disbursement request body | Disbursement log/account response | `loan_disbursement_request_log`, `loan_account` | Mock/provider adapter | Money movement simulation/integration point |
| POST | `/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome` | Apply mock disbursement outcome | `SYSTEM_ADMIN` | Method `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.applyMockDisbursementOutcome` | Outcome body | Updated disbursement/account response | `loan_disbursement_request_log`, `loan_account` | Mock provider | Test/mock path should be restricted in production |
| POST | `/api/v1/internal/ops/loan-applications/{applicationId}/payments` | Record payment | `SYSTEM_ADMIN` | Method `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.recordPayment` | `amount`, `paymentDate`, `reference`, `channel`, `status`, `note` | Payment response | `loan_payment_transaction`, schedule, account | None | Financial state mutation |
| POST | `/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes` | Create foreclosure quote | `SYSTEM_ADMIN` | Method `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.createForeclosureQuote` | `effectiveDate` | Quote response | `loan_foreclosure_quote`, schedule, account | None | Financial calculation |
| POST | `/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes/{quoteId}/execute` | Execute foreclosure | `SYSTEM_ADMIN` | Method `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.executeForeclosureQuote` | `settlementDate`, `reference`, `note` | Quote/account response | `loan_foreclosure_quote`, account, payments | Webhook outbox | Closes loan/account when valid |

### System Admin or Product Admin

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| GET | `/api/v1/internal/admin/products` | List loan products | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize` | `LoanProductAdminController.list` | Optional filters | List/envelope | `loan_product` | None | Product configuration surface |
| GET | `/api/v1/internal/admin/products/{productId}` | Product detail | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize` | `LoanProductAdminController.get` | Path `productId` | Product response | `loan_product` | None | Product configuration surface |
| POST | `/api/v1/internal/admin/products` | Create product | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize`, `@Valid` | `LoanProductAdminController.create` | Product terms/rates/status | Product response | `loan_product`, audit event | None | Terms affect downstream eligibility |
| PUT | `/api/v1/internal/admin/products/{productId}` | Update product | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize`, `@Valid` | `LoanProductAdminController.update` | Product terms/rates/status | Product response | `loan_product`, audit event | None | Terms affect downstream eligibility |
| GET | `/api/v1/internal/admin/products/{productId}/mappings` | Product-LSP mappings for product | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize` | `LoanProductAdminController.getMappings` | Path `productId` | Mapping response | `loan_product_lsp_mapping`, `lsp` | None | Controls LSP product availability |
| PUT | `/api/v1/internal/admin/products/{productId}/mappings` | Replace product-LSP mappings | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize`, `@Valid` | `LoanProductAdminController.updateMappings` | `lspIds` | Mapping response | `loan_product_lsp_mapping`, audit event | None | Controls LSP product availability |
| GET | `/api/v1/internal/admin/products/{productId}/audit-events` | Product audit events | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize` | `LoanProductAdminController.auditEvents` | Path `productId` | List | `loan_product_audit_event` | None | Audit history |
| GET | `/api/v1/internal/admin/product-lsp-mappings` | Product-LSP mapping overview | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize` | `ProductLspMappingAdminController.list` | Optional filters | List/envelope | `loan_product_lsp_mapping`, product, LSP | None | Controls LSP product availability |
| GET | `/api/v1/internal/admin/product-lsp-mappings/entries` | Mapping entry list | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize` | `ProductLspMappingAdminController.entries` | Optional filters | List/envelope | `loan_product_lsp_mapping` | None | Controls LSP product availability |
| PUT | `/api/v1/internal/admin/product-lsp-mappings/{productId}` | Replace mappings for product | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize`, `@Valid` | `ProductLspMappingAdminController.replaceForProduct` | `lspIds` | Mapping response | `loan_product_lsp_mapping`, audit event | None | Duplicates product controller capability |
| POST | `/api/v1/internal/admin/product-lsp-mappings/entries` | Upsert mapping entry | `SYSTEM_ADMIN`, `PRODUCT_ADMIN` | `@PreAuthorize`, `@Valid` | `ProductLspMappingAdminController.upsertEntry` | `lspId`, `productId`, `enabled` | Mapping entry response | `loan_product_lsp_mapping` | None | Per-entry mapping control |

### System Admin or Ops User

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| GET | `/api/v1/internal/admin/borrowers/{borrowerId}` | Borrower detail | `SYSTEM_ADMIN`, `OPS_USER` | `@PreAuthorize` | `BorrowerAdminController.get` | Path `borrowerId` | Borrower detail DTO | `borrower`, `borrower_lsp_access` | None | Contains PII |
| GET | `/api/v1/internal/alerts` | List ops alerts | `SYSTEM_ADMIN`, `OPS_USER` | `@PreAuthorize` | `OpsAlertController.list` | Optional filters/pagination | List/envelope | `ops_alert` | None | Operational alert feed |
| POST | `/api/v1/internal/alerts/{alertId}/acknowledge` | Acknowledge alert | `SYSTEM_ADMIN`, `OPS_USER` | `@PreAuthorize` | `OpsAlertController.acknowledge` | Path `alertId` | Alert response | `ops_alert` | None | Updates ack metadata |
| GET | `/api/v1/internal/ops/loan-applications` | List internal loan applications | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.list` | Filters, pagination, `paginationDetails` | List/envelope | `loan_application`, borrower, LSP, product | None | Contains borrower/loan data |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}` | Loan application detail | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.get` | Path id | Detail DTO | Loan application, borrower, product, account, docs | None | Contains PII and loan data |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/intake-audits` | Intake audit list | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.intakeAudits` | Path id | List | `loan_application_intake_audit` | None | Payload JSON may contain sensitive application data |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions` | Status transition history | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.statusTransitions` | Path id | List | `loan_application_status_transition` | None | Audit history |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/assignment-events` | Assignment history | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.assignmentEvents` | Path id | List | `loan_application_assignment_event` | None | Audit history |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/audit-events` | Loan audit events | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.auditEvents` | Path id | List | `loan_application_audit_event` | None | Audit history |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits` | Document access audit list | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.documentAccessAudits` | Path id | List | `loan_application_document_access_audit` | None | Audit of document downloads/views |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests` | Disbursement logs | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.disbursementRequests` | Path id | List | `loan_disbursement_request_log` | None | Money movement logs |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/repayment-schedule` | Repayment schedule | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.repaymentSchedule` | Path id | List | `loan_repayment_schedule_installment`, `loan_account` | None | Financial schedule |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/payments` | Payment transactions | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.payments` | Path id | List | `loan_payment_transaction` | None | Financial transactions |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents` | KYC document checklist | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.kycDocuments` | Path id | List | `loan_application_document_checklist` | Document storage metadata | Contains document metadata |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/download-all` | Download all KYC docs | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.downloadAllKycDocuments` | Path id | ZIP/binary or empty 404 | Document checklist | Local/R2 storage | Sensitive document export |
| GET | `/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}/content` | Download one KYC doc | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize` | `LoanApplicationOpsController.downloadKycDocument` | Path id/type | Binary or empty 404 | Document checklist | Local/R2 storage | Sensitive document export |
| POST | `/api/v1/internal/ops/loan-applications` | Create internal loan application | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.create` | Internal loan application body | Loan application response | Borrower, LSP, product, application, audits | Webhook outbox | Creates borrower/application records |
| POST | `/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions` | Transition application status | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize`, service/controller checks | `LoanApplicationOpsController.transitionStatus` | `targetStatus`, `note`, `reasonCode` | Loan application response | Application, status/audit, account maybe | Webhook outbox | `OPS_USER` limited to `INITIALIZED -> AWAITING_APPROVAL`; `SYSTEM_ADMIN` can allowed transitions |
| POST | `/api/v1/internal/ops/loan-applications/{applicationId}/assignment` | Assign loan application | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.assign` | `assigneeUsername`, `note` | Loan application response | Application, assignment event | None | Updates assignment metadata |
| PUT | `/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}` | Update KYC document checklist item | `SYSTEM_ADMIN`, `OPS_USER` | Class `@PreAuthorize`, `@Valid` | `LoanApplicationOpsController.updateKycDocument` | Status, note, file metadata, review/rejection reason | Document response | Document checklist, application | Local/R2 if managed content | Can affect KYC completion and lifecycle |

### System Admin, Ops User, or Product Admin

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| GET | `/api/v1/internal/admin/lsp-options` | LSP dropdown/options lookup | `SYSTEM_ADMIN`, `OPS_USER`, `PRODUCT_ADMIN` | `@PreAuthorize` | `LspOptionsController.list` | Optional query | List | `lsp` | None | Broad internal lookup |

### LSP API Client or LSP UI Read/Write

These endpoints require an authenticated principal with an LSP claim. `LspTenantContextInterceptor` sets tenant context for `/api/v1/lsp/**`. `LspIpAllowlistFilter` may apply CIDR allowlist checks for LSP API calls.

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| GET | `/api/v1/lsp/products` | Product catalog for authenticated LSP | `LSP_API_CLIENT`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspProductApiController.listProducts` | Optional filters | Product catalog list | `loan_product`, `loan_product_lsp_mapping`, `lsp` | None | Tenant-scoped by LSP claim/RLS |
| GET | `/api/v1/lsp/loans/{loanId}` | LSP loan account detail | `LSP_API_CLIENT`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspLoanApiController.getLoan` | Path `loanId` | Loan response | `loan_account`, application, borrower, product | None | Service checks loan belongs to LSP |
| GET | `/api/v1/lsp/loans/{loanId}/repayment-schedule` | LSP loan repayment schedule | `LSP_API_CLIENT`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspLoanApiController.getRepaymentSchedule` | Path `loanId` | Schedule list | `loan_repayment_schedule_installment`, `loan_account` | None | Service checks loan belongs to LSP |
| GET | `/api/v1/lsp/loans/{loanId}/payments` | LSP loan payments | `LSP_API_CLIENT`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspLoanApiController.getPayments` | Path `loanId` | Payment list | `loan_payment_transaction`, `loan_account` | None | Service checks loan belongs to LSP |
| GET | `/api/v1/lsp/loan-applications` | List LSP loan applications | `LSP_API_CLIENT`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspLoanApplicationApiController.list` | Filters/pagination | List/envelope | `loan_application`, borrower, product | None | Tenant-scoped |
| GET | `/api/v1/lsp/loan-applications/invalid-reasons` | Invalid reason code lookup | `LSP_API_CLIENT`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspLoanApplicationApiController.invalidReasons` | None | List | Enum/static lookup | None | Lookup endpoint |
| GET | `/api/v1/lsp/loan-applications/{applicationId}` | LSP loan application detail | `LSP_API_CLIENT`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspLoanApplicationApiController.get` | Path `applicationId` | Application response | Application, borrower, docs, account | None | Service checks application belongs to LSP |
| GET | `/api/v1/lsp/loan-applications/external/{externalLoanId}` | LSP loan application by external id | `LSP_API_CLIENT`, `LSP_UI_READ`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspLoanApplicationApiController.getByExternalId` | Path `externalLoanId` | Application response | `loan_application` | None | Scoped to current LSP |

### LSP API Client or LSP UI Write

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| POST | `/api/v1/lsp/loans/{loanId}/foreclosure-quote` | Request foreclosure quote for LSP loan | `LSP_API_CLIENT`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor, LSP write rate limit | `LspLoanApiController.createForeclosureQuote` | Path `loanId`, quote request | Quote response | `loan_foreclosure_quote`, `loan_account` | None | Financial calculation exposed to LSP |
| GET | `/api/v1/lsp/loan-applications/{applicationId}/borrower-pii` | Reveal borrower PII | `LSP_API_CLIENT`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor | `LspLoanApplicationApiController.borrowerPii` | Path `applicationId` | PII DTO | `borrower`, `loan_application`, `loan_application_pii_reveal_audit` | None | Audits reveal event; contains sensitive PII |
| POST | `/api/v1/lsp/loan-applications/{applicationId}/invalid` | Mark application invalid | `LSP_API_CLIENT`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor, LSP write rate limit, idempotency key | `LspLoanApplicationApiController.markInvalid` | Header `Idempotency-Key`, invalid reason body | Application response or replayed response | `loan_application`, `lsp_api_idempotency_record`, audits | Webhook outbox | Requires UUID v4 idempotency key |
| POST | `/api/v1/lsp/loan-applications/{applicationId}/documents` | Add document metadata JSON | `LSP_API_CLIENT`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor, LSP write rate limit | `LspLoanApplicationApiController.addDocumentMetadata` | JSON `documentType`, `fileName`, `fileReference`, etc. | Document response/application state | `loan_application_document_checklist` | None | Metadata-only path |
| POST | `/api/v1/lsp/loan-applications/{applicationId}/documents` | Upload one document multipart | `LSP_API_CLIENT`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor, LSP write rate limit | `LspLoanApplicationApiController.uploadDocument` | Query `documentType`; part `file`; optional note/source | Document response/application state | Document checklist | Local/R2 storage | Same path as JSON with multipart consumes |
| POST | `/api/v1/lsp/loan-applications/{applicationId}/documents/batch` | Upload batch documents multipart | `LSP_API_CLIENT`, `LSP_UI_WRITE` | `@PreAuthorize`, tenant interceptor, LSP write rate limit | `LspLoanApplicationApiController.uploadDocumentBatch` | Part `documents`, part `files` with matching counts | Batch document response | Document checklist | Local/R2 storage | Needs file size/type review |

### LSP API Client Only

| Method | Full path | Purpose | Access level/role | Middleware/guard/decorator | Handler/controller | Request params/body | Response shape | Database/models used | External services used | Notes/security concerns |
|---|---|---|---|---|---|---|---|---|---|---|
| POST | `/api/v1/lsp/loan-applications` | Create loan application from LSP API | `LSP_API_CLIENT` | `@PreAuthorize`, tenant interceptor, IP allowlist, LSP write rate limit | `LspLoanApplicationApiController.create` | LSP loan application body | Application response | Borrower, borrower access, LSP, product, application, audit, docs | Webhook outbox | Body `lspId` must match authenticated LSP |
| PUT | `/api/v1/lsp/loan-applications/{applicationId}/repayment-schedule` | Upsert repayment schedule | `LSP_API_CLIENT` | `@PreAuthorize`, tenant interceptor, IP allowlist, LSP write rate limit | `LspLoanApplicationApiController.upsertRepaymentSchedule` | `mode`, installments | Schedule/application response | `loan_repayment_schedule_installment`, `loan_account` | None | Requires application/account belongs to LSP |
| POST | `/api/v1/lsp/loan-applications/{applicationId}/disbursement` | Trigger LSP disbursement flow | `LSP_API_CLIENT` | `@PreAuthorize`, tenant interceptor, IP allowlist, LSP write rate limit | `LspLoanApplicationApiController.disburse` | `disbursalAmount`, bank details | Disbursement/account response | Loan application/account, schedule, disbursement log | Mock/provider adapter, webhook outbox | Money movement integration point |

## 5. Endpoint Deep Dive

### Auth Login and API Client Token

| Field | Details |
|---|---|
| Endpoints | `POST /api/v1/auth/login`, `POST /api/v1/auth/token` |
| Purpose | Issue access JWTs and refresh cookies for managed users and LSP API clients |
| Access | Public, rate-limited |
| Handler | `AuthController` |
| Middleware/guards | `SecurityConfig.permitAll`, `RateLimitFilter` for auth endpoints |
| Request format | Login uses `username` and `password`; client token uses `clientId` and `clientSecret` |
| Response format | `TokenResponse` with access token, token type, expiry, password-change flag where relevant, plus HttpOnly refresh cookie |
| Backend flow | Credentials are authenticated, user/API client state is checked, JWT claims are minted, refresh token hash is persisted, refresh cookie is returned |
| Validation/security notes | Passwords/client secrets use bcrypt. Refresh token values are stored hashed. API clients must be active and tied to an LSP. Login can issue `ROLE_PASSWORD_CHANGE_REQUIRED` and restrict other `/api/v1/**` APIs until password is changed |

### Internal Loan Application Operations

| Field | Details |
|---|---|
| Endpoints | `/api/v1/internal/ops/loan-applications/**` |
| Purpose | Internal back-office create, review, transition, document, payment, disbursement, and foreclosure workflows |
| Access | Mostly `SYSTEM_ADMIN` and `OPS_USER`; high-risk financial mutations are `SYSTEM_ADMIN` only |
| Handler | `LoanApplicationOpsController` |
| Middleware/guards | JWT auth, password-change block, class and method `@PreAuthorize`, Bean Validation |
| Request format | JSON request records for application creation, status transition, assignment, document update, disbursement, payment, foreclosure |
| Response format | DTOs from `LoanApplicationOpsResponses`, lists/envelopes, binary document responses for downloads |
| Backend flow | Controller validates input and access, service loads application/account, lifecycle service validates state transitions, repositories persist state and audit rows, webhooks are enqueued for subscribed LSPs |
| Validation/security notes | Contains PII and financial state. `OPS_USER` status transition is narrower than `SYSTEM_ADMIN`: observed controller logic permits ops users only for `INITIALIZED -> AWAITING_APPROVAL` while system admins can perform allowed transitions |

### LSP Loan Application Create

| Field | Details |
|---|---|
| Endpoint | `POST /api/v1/lsp/loan-applications` |
| Purpose | Let an authenticated LSP API client submit a loan application |
| Access | `LSP_API_CLIENT` only |
| Handler | `LspLoanApplicationApiController.create` |
| Middleware/guards | JWT auth, `@PreAuthorize`, `LspTenantContextInterceptor`, `LspIpAllowlistFilter`, `RateLimitFilter`, Bean Validation |
| Request format | JSON containing LSP/product/external loan identifiers, borrower identity/contact/KYC/employment/bank/reference fields, requested loan amount/rate/tenure. The request requires either `productId` or `loanProduct`, and either monthly or annual income |
| Response format | LSP loan application response DTO |
| Backend flow | Authenticated LSP context is extracted, request LSP id is compared with token LSP id, product and LSP mapping are validated, borrower conflicts and open-loan constraints are checked, borrower/access/application/audit/document checklist rows are saved, webhook events may be queued |
| Validation/security notes | Tenant isolation depends on JWT LSP claim, service checks, and PostgreSQL RLS context. Duplicate external loan ids are constrained per LSP |

### LSP Document Upload

| Field | Details |
|---|---|
| Endpoints | `POST /api/v1/lsp/loan-applications/{applicationId}/documents`, `POST /api/v1/lsp/loan-applications/{applicationId}/documents/batch` |
| Purpose | Attach KYC document metadata or file content to a loan application |
| Access | `LSP_API_CLIENT` or `LSP_UI_WRITE` |
| Handler | `LspLoanApplicationApiController` |
| Middleware/guards | JWT auth, `@PreAuthorize`, tenant interceptor, LSP write rate limit |
| Request format | JSON metadata request, single multipart file request, or batch multipart request with metadata list and matching files |
| Response format | Document response or batch document response |
| Backend flow | Service loads tenant-scoped application, validates document type/status, stores file in local/R2 storage when uploaded, updates checklist metadata, writes audit/access records where applicable, may advance workflow when required LMS-managed documents are complete |
| Validation/security notes | No clear file type allowlist, malware scan, or maximum upload size was found in inspected code. Needs review |

### LSP Disbursement

| Field | Details |
|---|---|
| Endpoint | `POST /api/v1/lsp/loan-applications/{applicationId}/disbursement` |
| Purpose | Let LSP API client initiate disbursement for an approved or auto-approvable application |
| Access | `LSP_API_CLIENT` only |
| Handler | `LspLoanApplicationApiController.disburse` |
| Middleware/guards | JWT auth, `@PreAuthorize`, tenant interceptor, LSP write rate limit, IP allowlist |
| Request format | `disbursalAmount`, `bankAccountNumber`, `ifscCode`, `accountHolderName` |
| Response format | Disbursement/account response |
| Backend flow | Service checks application ownership and readiness, may auto-approve if eligible, validates LMS-managed documents, bank details, amount, product fee/rate rules, and repayment schedule, creates disbursement log, invokes configured/mock provider behavior, updates account/application state, queues webhooks |
| Validation/security notes | This is a money-movement integration point. Provider integration currently includes mock outcome handling; production provider behavior needs review |

### Reports

| Field | Details |
|---|---|
| Endpoints | `/api/v1/internal/reports/**` |
| Purpose | Portfolio MIS preview, CSV download, async report request, and report download |
| Access | `SYSTEM_ADMIN` |
| Handler | `ReportAdminController` |
| Middleware/guards | JWT auth, `@PreAuthorize`, validation |
| Request format | Query params for LSP/date filters/pagination; async body with optional email recipient |
| Response format | List/envelope, summary DTO, CSV text, or report request DTO |
| Backend flow | Synchronous endpoints query reporting repositories and build CSV/summary; async endpoint inserts a `report_request`, scheduled worker claims requests, generates report content, stores it, and optionally sends email notification |
| Validation/security notes | Report content can include PII/financial data and is stored in the database. Email notification must be configured carefully |

### Webhook Outbox

| Field | Details |
|---|---|
| Endpoints | Internal admin list/dispatch plus scheduled worker |
| Purpose | Deliver lifecycle events to subscribed LSP webhook endpoints |
| Access | Manual dispatch/list requires `SYSTEM_ADMIN`; scheduled worker runs internally |
| Handler/service | `WebhookOutboxAdminController`, `WebhookOutboxService`, `WebhookOutboxDispatchWorker`, `HttpWebhookDeliveryClient` |
| Middleware/guards | JWT auth for admin endpoints, scheduled worker property gate for background delivery |
| Request format | Admin list filters and manual dispatch request; outbound webhook POST JSON to LSP endpoint |
| Response format | Outbox list/dispatch summary; outbound HTTP status captured in delivery attempt table |
| Backend flow | Business services enqueue events, worker/admin dispatch claims pending rows, SSRF-safe URL validator checks URL, HMAC signature headers are generated, HTTP POST is sent, attempt is persisted, event is marked delivered/retryable/permanent failure |
| Validation/security notes | Signing secrets are stored in `lsp.webhook_signing_secret`. URL validator behavior should be reviewed for DNS failure/rebinding edge cases |

## 6. Authentication Flow

### Managed User Login

1. Client posts `username` and `password` to `POST /api/v1/auth/login`.
2. `AuthController` delegates to Spring Security authentication.
3. `AppUserDetailsService` loads a managed `app_user` or configured bootstrap user.
4. `DaoAuthenticationProvider` verifies the bcrypt password.
5. `JwtTokenService` issues an access JWT with username, auth type, roles, optional LSP claims, password-change flag, and password-version claim.
6. A refresh token is generated, hashed, and stored in `refresh_token`.
7. Response returns `TokenResponse` and an HttpOnly refresh cookie named `lms-refresh`.

### API Client Token

1. LSP client posts `clientId` and `clientSecret` to `POST /api/v1/auth/token`.
2. `ApiClientAuthenticationService` loads the `api_client`, validates active status, validates the bcrypt secret hash, and updates last-used metadata.
3. `JwtTokenService` issues an access JWT with `LSP_API_CLIENT`, auth type, client id/name, and LSP id/code claims.
4. Refresh token storage and cookie behavior is the same as managed user login.

### Refresh and Logout

1. `POST /api/v1/auth/refresh` reads `lms-refresh`, hashes it, finds a non-revoked unexpired `refresh_token`, revokes the old row, validates the current user/API client context, and issues a new access token plus rotated refresh cookie.
2. `POST /api/v1/auth/logout` revokes the refresh token when present and clears the cookie.

### Password Change

1. A managed user with `passwordChangeRequired` calls `POST /api/v1/auth/password`.
2. Request requires `newPassword` with length 12 to 128.
3. Service rejects reuse of the temporary password, stores a bcrypt hash, clears `passwordChangeRequired`, updates password version timestamp, and issues fresh tokens.
4. `SecurityConfig` blocks other `/api/v1/**` routes while `ROLE_PASSWORD_CHANGE_REQUIRED` is present.

### Main Auth Files

| File | Role in auth |
|---|---|
| `security/SecurityConfig.java` | Security filter chain, URL rules, JWT decoder/converter, auth entry point, CORS |
| `security/SecurityProperties.java` | JWT, bootstrap user, cookie, CORS, and security-related config |
| `security/AppUserDetailsService.java` | Managed/bootstrap user lookup |
| `security/JwtTokenService.java` | Access and refresh token issuance/validation |
| `service/ApiClientAuthenticationService.java` | API client credential validation |
| `web/AuthController.java` | Auth HTTP endpoints |
| `domain/RefreshToken.java` | Refresh token persistence model |
| `repo/RefreshTokenRepository.java` | Refresh token data access |

## 7. Authorization and Access Control

### Roles

| Role | Observed purpose |
|---|---|
| `SYSTEM_ADMIN` | Full internal administration, reports, user/API client/LSP/product config, sensitive loan ops |
| `OPS_USER` | Internal loan operations, borrower detail, alerts, limited status transitions |
| `PRODUCT_ADMIN` | Product and product-to-LSP mapping management; LSP options |
| `LSP_UI_READ` | Read-only LSP UI/API views and system context |
| `LSP_UI_WRITE` | LSP UI write operations such as document upload, invalid marking, PII reveal, foreclosure quote |
| `LSP_API_CLIENT` | LSP server-to-server API operations, including create application, schedules, and disbursement |

### URL-Level Rules

`SecurityConfig` permits health/info, Swagger/OpenAPI, login, token, refresh, and logout. All other `/api/v1/**` requests must be authenticated and must not have `ROLE_PASSWORD_CHANGE_REQUIRED`.

### Route-Level and Method-Level Rules

Controllers use `@PreAuthorize` for role checks. Some controllers have class-level checks and method-level overrides for more sensitive methods.

### Service-Level Checks

| Area | Service-level control |
|---|---|
| LSP application and loan access | Services load by current LSP id or verify entity LSP id matches JWT LSP claim |
| LSP create application | Request LSP id must match authenticated LSP id |
| LSP product availability | Product must be active and mapped/enabled for the authenticated LSP |
| Ops status transitions | Controller/service restrict status graph; ops users have narrower transition permission than system admins |
| Borrower visibility | PostgreSQL RLS and `borrower_lsp_access` model control LSP-specific borrower visibility |
| Tenant data access | LSP routes set tenant context that flows into tenant-aware datasource and RLS session settings |
| Idempotency | Selected LSP mutation endpoint stores and replays idempotent responses using idempotency key and request fingerprint |

### Unclear Areas

| Area | Status |
|---|---|
| `app_permission` and `app_role_permission` active use | Needs review. Tables exist, but access decisions observed in code are role-based rather than permission-based |
| Production CORS origins | Needs review. Localhost origins are visible in config |
| Generated Graphify files under `src/main/java` | Needs review. They appear non-runtime |

## 8. Database Schema

The database is managed through Flyway migrations under `backend/src/main/resources/db/migration` and JPA entities under `backend/src/main/java/com/bhawana/lms/domain`.

### `lsp`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | LSP primary key |
| `code` | text/varchar, unique, required | LSP code |
| `name` | text/varchar, required | LSP display name |
| `status` | enum/text, required | Active/inactive style status |
| `webhook_enabled` | boolean | Webhook subscription switch |
| `webhook_endpoint_url` | text, nullable | Outbound webhook URL |
| `webhook_signing_secret` | text, nullable | Sensitive HMAC signing secret |
| `webhook_event_types` | text/array/json depending migration | Subscribed event types |
| `created_at`, `updated_at` | timestamp | Audit timestamps |

Connected endpoints/services: LSP admin, API client auth, LSP APIs, reports, webhooks, product mappings, tenant isolation.

### `app_role`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Role id |
| `code` | text/varchar, unique, required | Matches `AppRoleCode` |
| `description` | text, nullable | Description |
| `created_at` | timestamp | Audit timestamp |

Connected endpoints/services: user admin, security, role bootstrap.

### `app_permission`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Permission id |
| `code` | text/varchar, unique, required | Permission code |
| `description` | text, nullable | Description |
| `created_at` | timestamp | Audit timestamp |

Connected endpoints/services: role permission table. Needs review because direct permission checks were not found in inspected controller/security code.

### `app_user`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | User id |
| `lsp_id` | UUID, nullable FK -> `lsp.id` | Present for LSP UI users |
| `username` | text/varchar, unique, required | Login username |
| `email` | text/varchar, unique nullable | User email |
| `password_hash` | text/varchar, required | Bcrypt hash |
| `password_change_required` | boolean | Forces password change |
| `password_changed_at` | timestamp, nullable | Used as password version claim |
| `status` | enum/text, required | User status |
| `created_at`, `updated_at` | timestamp | Audit timestamps |

Connected endpoints/services: auth, user admin, JWT password version validation.

### `app_user_role`

| Field | Type/status | Notes |
|---|---|---|
| `user_id` | UUID, PK part, FK -> `app_user.id` | User |
| `role_id` | UUID, PK part, FK -> `app_role.id` | Role |
| `created_at` | timestamp | Audit timestamp |

Connected endpoints/services: auth, user admin.

### `app_role_permission`

| Field | Type/status | Notes |
|---|---|---|
| `role_id` | UUID, PK part, FK -> `app_role.id` | Role |
| `permission_id` | UUID, PK part, FK -> `app_permission.id` | Permission |
| `created_at` | timestamp | Audit timestamp |

Connected endpoints/services: Needs review. Table exists but role checks are the observed active authorization mechanism.

### `loan_product`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Product id |
| `code` | text/varchar, unique, required | Product code |
| `name` | text/varchar, required | Product name |
| `min_principal`, `max_principal` | numeric, required | Amount range |
| `interest_rate` | numeric, required | Interest rate |
| `processing_fee_rate` | numeric, required | Fee rate |
| `min_tenure_months`, `max_tenure_months` | integer, required | Tenure range |
| `status` | enum/text, required | Product status |
| `created_at`, `updated_at` | timestamp | Audit timestamps |

Connected endpoints/services: product admin, LSP product catalog, loan application validation.

### `loan_product_lsp_mapping`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Mapping id |
| `loan_product_id` | UUID, FK -> `loan_product.id` | Product |
| `lsp_id` | UUID, FK -> `lsp.id` | LSP |
| `enabled` | boolean | Availability switch |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| Unique constraint | `(loan_product_id, lsp_id)` | Prevents duplicate mapping |

Connected endpoints/services: product mapping admin, LSP product catalog, loan create validation.

### `loan_product_audit_event`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Event id |
| `loan_product_id` | UUID, FK -> `loan_product.id` | Product |
| `action` | text/varchar, required | Action |
| `actor_username` | text/varchar | Actor |
| `summary` | text | Summary |
| `correlation_id` | text/varchar | Request correlation id |
| `created_at` | timestamp | Event time |

Connected endpoints/services: product admin audit endpoints.

### `borrower`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Borrower id |
| `full_name` | text/varchar, required | Borrower name |
| `pan` | text/varchar, unique, required | PAN |
| `mobile` | text/varchar | Mobile |
| `email` | text/varchar, nullable | Email |
| `date_of_birth` | date | DOB |
| `gender`, `marital_status`, `father_name`, `spouse_name` | text/varchar, nullable | Personal details |
| `aadhar_number` | text/varchar, nullable | Sensitive identifier |
| `city`, `state`, `address_line_1`, `address_line_2`, `address_zip_code` | text/varchar, nullable | Address |
| `employment_type`, `organization_name`, `employee_id` | text/varchar, nullable | Employment |
| `employment_city`, `employment_state`, `employment_zip_code` | text/varchar, nullable | Employment location |
| `monthly_income`, `annual_income` | numeric, nullable | Income |
| `bank_account_number`, `bank_name`, `ifsc_code`, `account_holder_name` | text/varchar, nullable | Bank details |
| `reference_person_name`, `reference_person_number` | text/varchar, nullable | Reference |
| `created_at`, `updated_at` | timestamp | Audit timestamps |

Connected endpoints/services: loan applications, borrower admin, LSP PII reveal, reports. Later migrations make borrowers global and use `borrower_lsp_access` for LSP visibility.

### `borrower_lsp_access`

| Field | Type/status | Notes |
|---|---|---|
| `borrower_id` | UUID, PK part, FK -> `borrower.id` | Borrower |
| `lsp_id` | UUID, PK part, FK -> `lsp.id` | LSP |
| `created_at` | timestamp | Access grant time |

Connected endpoints/services: LSP borrower visibility, tenant/RLS policies.

### `loan_application`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Application id |
| `borrower_id` | UUID, FK -> `borrower.id` | Borrower |
| `lsp_id` | UUID, FK -> `lsp.id` | LSP |
| `loan_product_id` | UUID, FK -> `loan_product.id` | Product |
| `external_loan_id` | text/varchar, required | LSP external loan id |
| `source_channel` | text/varchar | Source |
| `requested_amount` | numeric, required | Requested amount |
| `tenure_months` | integer, required | Tenure |
| `status` | enum/text, required | Application lifecycle status |
| `invalid_reason_code`, `invalid_reason_text` | text/varchar, nullable | Invalid reason |
| `invalidated_by_username`, `invalidated_at` | text/timestamp nullable | Invalid audit metadata |
| `assigned_to`, `assigned_by`, `assigned_at` | text/timestamp nullable | Assignment metadata |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| `entity_version` | integer/bigint | Optimistic locking |
| Unique constraint | `(lsp_id, external_loan_id)` | LSP-scoped external id uniqueness |

Connected endpoints/services: LSP application API, internal ops, reports, webhooks, loan account creation.

### `loan_application_intake_audit`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Audit id |
| `loan_application_id` | UUID, FK -> `loan_application.id` | Application |
| `actor_username` | text/varchar | Actor |
| `correlation_id` | text/varchar | Correlation |
| `payload_json` | JSON/text | Original intake payload |
| `created_at` | timestamp | Event time |

Connected endpoints/services: application create audit, ops audit endpoint. Contains sensitive payload data.

### `loan_application_status_transition`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Transition id |
| `loan_application_id` | UUID, FK -> `loan_application.id` | Application |
| `from_status`, `to_status` | enum/text | Status change |
| `actor_username` | text/varchar | Actor |
| `note`, `reason_code` | text/varchar nullable | Reason metadata |
| `correlation_id` | text/varchar | Correlation |
| `created_at` | timestamp | Event time |

Connected endpoints/services: lifecycle service, ops status history.

### `loan_application_assignment_event`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Assignment event id |
| `loan_application_id` | UUID, FK -> `loan_application.id` | Application |
| `from_assignee_username`, `to_assignee_username` | text/varchar nullable | Assignment change |
| `actor_username` | text/varchar | Actor |
| `note` | text nullable | Note |
| `correlation_id` | text/varchar | Correlation |
| `created_at` | timestamp | Event time |

Connected endpoints/services: ops assignment.

### `loan_application_document_checklist`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Checklist row id |
| `loan_application_id` | UUID, FK -> `loan_application.id` | Application |
| `document_type` | enum/text, required | Document type |
| `required` | boolean | Required flag |
| `status` | enum/text, required | Document lifecycle status |
| `note` | text nullable | Note |
| `updated_by_username` | text/varchar nullable | Last actor |
| `file_name`, `file_reference`, `source_reference`, `content_type` | text/varchar nullable | File metadata |
| `uploaded_at`, `uploaded_by_username` | timestamp/text nullable | Upload metadata |
| `review_reason`, `rejection_reason` | text nullable | Review metadata |
| `lms_managed_content` | boolean | Whether file content is stored by LMS |
| `storage_key` | text/varchar nullable | Storage object key/path |
| `file_checksum` | text/varchar nullable | Checksum |
| `file_size_bytes` | bigint nullable | Size |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| Unique constraint | `(loan_application_id, document_type)` | One checklist item per document type |

Connected endpoints/services: document upload/update/download, KYC workflow, document access audit.

### `loan_application_audit_event`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Audit id |
| `loan_application_id` | UUID, FK -> `loan_application.id` | Application |
| `action` | text/varchar | Action |
| `actor_username` | text/varchar | Actor |
| `from_status`, `to_status` | enum/text nullable | Status metadata |
| `note`, `reason_code` | text nullable | Reason metadata |
| `correlation_id` | text/varchar | Correlation |
| `created_at` | timestamp | Event time |

Connected endpoints/services: lifecycle and ops audit views.

### `loan_application_document_access_audit`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Audit id |
| `loan_application_id` | UUID, FK -> `loan_application.id` | Application |
| `action` | text/varchar | Access action |
| `actor_username` | text/varchar | Actor |
| `summary` | text | Summary |
| `document_types` | text/json | Accessed document types |
| `correlation_id` | text/varchar | Correlation |
| `created_at` | timestamp | Event time |

Connected endpoints/services: document download/access APIs.

### `loan_application_pii_reveal_audit`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Audit id |
| `loan_application_id` | UUID, FK -> `loan_application.id` | Application |
| `lsp_id` | UUID, FK -> `lsp.id` | LSP |
| `actor_username` | text/varchar | Actor |
| `revealed_fields` | text/json | Revealed field list |
| `correlation_id` | text/varchar | Correlation |
| `created_at` | timestamp | Event time |

Connected endpoints/services: LSP borrower PII endpoint.

### `loan_account`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Loan account id |
| `loan_application_id` | UUID, unique FK -> `loan_application.id` | Source application |
| `borrower_id` | UUID, FK -> `borrower.id` | Borrower |
| `lsp_id` | UUID, FK -> `lsp.id` | LSP |
| `loan_product_id` | UUID, FK -> `loan_product.id` | Product |
| `account_number` | text/varchar, unique | Account number |
| `principal_amount` | numeric | Principal |
| `tenure_months` | integer | Tenure |
| `status` | enum/text | Account status |
| `approved_at`, `disbursed_at`, `closed_at` | timestamp nullable | Lifecycle times |
| `closure_reason`, `closed_by_username` | text/varchar nullable | Closure metadata |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| `entity_version` | integer/bigint | Optimistic locking |

Connected endpoints/services: approval, disbursement, repayment, foreclosure, reports.

### `loan_repayment_schedule_installment`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Installment id |
| `loan_account_id` | UUID, FK -> `loan_account.id` | Loan account |
| `installment_number` | integer | Installment number |
| `due_date` | date | Due date |
| `opening_principal`, `principal_due`, `interest_due`, `installment_amount`, `closing_principal` | numeric | Schedule amounts |
| `status` | enum/text | Installment status |
| `paid_principal`, `paid_interest`, `paid_amount`, `outstanding_amount` | numeric | Allocation state |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| Unique constraint | `(loan_account_id, installment_number)` | One installment number per account |

Connected endpoints/services: repayment schedule, payments, foreclosure.

### `loan_disbursement_request_log`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Request log id |
| `loan_account_id` | UUID, FK -> `loan_account.id` | Loan account |
| `actor_username` | text/varchar | Actor |
| `amount` | numeric | Disbursement amount |
| `provider_name` | text/varchar | Provider |
| `provider_request_id` | text/varchar nullable | Provider request id |
| `provider_status` | enum/text | Provider status |
| `request_payload_json`, `response_payload_json` | JSON/text nullable | Provider payloads |
| `correlation_id` | text/varchar | Correlation |
| `created_at`, `updated_at` | timestamp | Audit timestamps |

Connected endpoints/services: disbursement endpoints and logs.

### `loan_payment_transaction`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Payment id |
| `loan_account_id` | UUID, FK -> `loan_account.id` | Loan account |
| `repayment_installment_id` | UUID, nullable FK -> installment | Allocated installment |
| `actor_username` | text/varchar | Actor |
| `amount` | numeric | Payment amount |
| `payment_date` | date | Payment date |
| `reference` | text/varchar | Payment reference |
| `channel` | text/varchar | Channel |
| `status` | enum/text | Payment status |
| `note` | text nullable | Note |
| `allocated_amount`, `unallocated_amount` | numeric | Allocation state |
| `correlation_id` | text/varchar | Correlation |
| `created_at`, `updated_at` | timestamp | Audit timestamps |

Connected endpoints/services: payment recording, repayment schedule, foreclosure.

### `loan_foreclosure_quote`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Quote id |
| `loan_account_id` | UUID, FK -> `loan_account.id` | Loan account |
| `version` | integer | Quote version |
| `requested_by_username`, `executed_by_username` | text/varchar nullable | Actors |
| `effective_date` | date | Quote effective date |
| `outstanding_principal`, `outstanding_interest`, `settlement_amount` | numeric | Quote amounts |
| `status` | enum/text | Quote status |
| `executed_at` | timestamp nullable | Execution time |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| Unique constraint | `(loan_account_id, version)` | Quote versioning |

Connected endpoints/services: foreclosure endpoints.

### `api_client`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | API client id |
| `client_id` | text/varchar, unique | Public client id |
| `lsp_id` | UUID, FK -> `lsp.id` | Owning LSP |
| `name`, `description` | text/varchar | Metadata |
| `secret_hash` | text/varchar | Bcrypt secret hash |
| `status` | enum/text | Client status |
| `created_at`, `updated_at`, `last_used_at` | timestamp | Audit metadata |

Connected endpoints/services: API client admin, API client auth.

### `lsp_api_idempotency_record`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Record id |
| `lsp_id` | UUID, FK -> `lsp.id` | LSP |
| `operation_key` | text/varchar | Operation identifier |
| `idempotency_key` | UUID/text | Client idempotency key |
| `request_fingerprint` | text/varchar | Request hash |
| `response_status` | integer | Stored HTTP status |
| `response_body` | JSON/text | Stored response body |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| Unique constraint | `(lsp_id, operation_key, idempotency_key)` | Replay key |

Connected endpoints/services: LSP invalid marking and any other idempotent LSP mutation using the service.

### `lsp_ip_allowlist`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Entry id |
| `lsp_id` | UUID, FK -> `lsp.id` | LSP |
| `cidr` | text/varchar | Allowed CIDR |
| `description` | text nullable | Admin description |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| Unique constraint | `(lsp_id, cidr)` | Prevents duplicate CIDR |

Connected endpoints/services: LSP allowlist admin, IP allowlist filter.

### `webhook_event_outbox`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Event id |
| `lsp_id` | UUID, FK -> `lsp.id` | Target LSP |
| `event_type` | text/varchar | Event type |
| `aggregate_type`, `aggregate_id` | text/UUID | Aggregate reference |
| `status` | enum/text | Pending/delivered/retry/permanent failure style status |
| `payload_json` | JSON/text | Outbound payload |
| `correlation_id` | text/varchar | Correlation |
| `attempt_count` | integer | Attempts |
| `last_attempt_at`, `next_attempt_at`, `delivered_at` | timestamp nullable | Delivery schedule |
| `last_error` | text nullable | Last error |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| `entity_version` | integer/bigint | Optimistic locking |

Connected endpoints/services: lifecycle services, webhook worker/admin.

### `webhook_event_delivery_attempt`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Attempt id |
| `outbox_event_id` | UUID, FK -> `webhook_event_outbox.id` | Event |
| `attempt_number` | integer | Attempt number |
| `request_url`, `request_event_type`, `request_delivery_id`, `request_timestamp`, `request_signature` | text/varchar | Outbound request metadata |
| `response_status_code` | integer nullable | HTTP status |
| `response_body` | text nullable | Response body |
| `error_message` | text nullable | Error |
| `status` | enum/text | Attempt status |
| `created_at` | timestamp | Attempt time |

Connected endpoints/services: webhook delivery diagnostics.

### `report_request`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Report request id |
| `report_type` | enum/text | Report type |
| `status` | enum/text | Pending/processing/completed/failed |
| `lsp_id` | UUID, nullable FK -> `lsp.id` | Optional LSP filter |
| `disbursal_date_from`, `disbursal_date_to` | date nullable | Filter |
| `requested_by_username` | text/varchar | Requester |
| `notification_email`, `notification_sent_at`, `notification_error` | text/timestamp nullable | Email status |
| `file_name` | text/varchar nullable | Output file name |
| `media_type` | text/varchar nullable | Output media type |
| `report_content` | text/blob nullable | Generated report content |
| `error_message` | text nullable | Failure message |
| `completed_at` | timestamp nullable | Completion time |
| `created_at`, `updated_at` | timestamp | Audit timestamps |
| `entity_version` | integer/bigint | Optimistic locking |

Connected endpoints/services: report endpoints, report worker, email notifications.

### `ops_alert`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Alert id |
| `type`, `severity`, `status` | enum/text | Alert classification |
| `title`, `message` | text/varchar | Alert content |
| `subject_type`, `subject_id` | text/UUID | Related object reference without FK |
| `correlation_id` | text/varchar | Correlation |
| `context_json` | JSON/text nullable | Context |
| `created_at` | timestamp | Created time |
| `acknowledged_at`, `acknowledged_by` | timestamp/text nullable | Ack metadata |

Connected endpoints/services: ops alerts.

### `refresh_token`

| Field | Type/status | Notes |
|---|---|---|
| `id` | UUID, PK | Token id |
| `token_hash` | text/varchar, unique | Hash of refresh token value |
| `username` | text/varchar | Principal username/client id |
| `auth_type` | text/varchar | Managed user or API client |
| `expires_at` | timestamp | Expiry |
| `revoked` | boolean | Revocation flag |
| `created_at` | timestamp | Created time |

Connected endpoints/services: auth refresh/logout. No direct FK to user/client was found in the summarized schema, so username/client id integrity is application-managed.

### RLS, Indexes, and Hardening

| Area | Finding |
|---|---|
| Tenant RLS | Migrations enable row-level security for tenant-sensitive tables using `app_current_lsp_id()` populated by tenant-aware datasource |
| Borrower visibility | Later migrations move from direct borrower tenant ownership to global borrower plus `borrower_lsp_access` |
| Indexes | Migrations add indexes for loan application filters, loan account disbursal/reporting, webhook dispatch, report processing, borrower search/trigram behavior, and tenant lookups |
| Hardening migrations | Some migrations specifically harden RLS/index behavior. Needs review for exact production posture |

## 9. Table / Model Relationships

| Source | Relationship | Target | Notes |
|---|---|---|---|
| `lsp` | 1 -> many | `app_user` | LSP UI users may be tied to one LSP |
| `app_user` | many -> many | `app_role` | Through `app_user_role` |
| `app_role` | many -> many | `app_permission` | Through `app_role_permission`; active use needs review |
| `lsp` | 1 -> many | `api_client` | API clients authenticate on behalf of an LSP |
| `loan_product` | many -> many | `lsp` | Through `loan_product_lsp_mapping` |
| `borrower` | many -> many | `lsp` | Through `borrower_lsp_access` |
| `borrower` | 1 -> many | `loan_application` | Borrower can have applications |
| `lsp` | 1 -> many | `loan_application` | Application belongs to submitting LSP |
| `loan_product` | 1 -> many | `loan_application` | Application uses a product |
| `loan_application` | 1 -> 1 | `loan_account` | Account is created after approval |
| `loan_application` | 1 -> many | Intake/status/assignment/audit/document/PII audit tables | Workflow history |
| `loan_account` | 1 -> many | `loan_repayment_schedule_installment` | Repayment schedule |
| `loan_account` | 1 -> many | `loan_payment_transaction` | Payments |
| `loan_account` | 1 -> many | `loan_disbursement_request_log` | Disbursement logs |
| `loan_account` | 1 -> many | `loan_foreclosure_quote` | Foreclosure quotes |
| `lsp` | 1 -> many | `webhook_event_outbox` | Target webhook events |
| `webhook_event_outbox` | 1 -> many | `webhook_event_delivery_attempt` | Delivery attempts |
| `lsp` | 1 -> many | `report_request` | Optional report filter |
| `ops_alert` | loose reference | subject table | `subject_type` and `subject_id` are not enforced by FK in inspected schema |
| `refresh_token` | application-managed reference | user/client | Stores username/client id and auth type, not a direct FK |

```txt
LSP
  -> API clients
  -> LSP UI users
  -> product mappings -> loan products
  -> borrower_lsp_access -> borrowers
  -> loan applications -> loan accounts
       -> repayment installments
       -> payment transactions
       -> disbursement logs
       -> foreclosure quotes
       -> document checklist and audit tables
  -> webhook outbox -> delivery attempts

App user -> app_user_role -> app_role
App role -> app_role_permission -> app_permission (Needs review for active use)
```

## 10. Backend Request and Data Flow

### Standard Request Flow

```txt
HTTP request
  -> CorrelationIdFilter
  -> Security filters / JWT authentication
  -> RateLimitFilter and LspIpAllowlistFilter where applicable
  -> LspTenantContextInterceptor for /api/v1/lsp/**
  -> Controller @PreAuthorize and @Valid
  -> Service/business workflow
  -> Repository/JPA/Flyway-backed PostgreSQL
  -> Optional storage/webhook/email integration
  -> DTO/envelope/binary response
  -> GlobalExceptionHandler for errors
```

### LSP Application Intake Flow

```txt
LSP API token
  -> POST /api/v1/lsp/loan-applications
  -> tenant context set from JWT LSP claim
  -> request LSP/product/borrower/application validation
  -> borrower and borrower_lsp_access upsert/create
  -> loan_application create
  -> intake audit and document checklist create
  -> webhook outbox event queued
  -> application response
```

### Internal Review and Approval Flow

```txt
Internal authenticated user
  -> list/detail/transition document APIs
  -> role and status transition checks
  -> lifecycle service updates application status
  -> status transition and audit rows written
  -> loan account created on approval path when applicable
  -> webhooks queued for subscribed LSP
```

### Disbursement Flow

```txt
LSP API or system admin request
  -> application/account/product/document/bank/schedule validation
  -> disbursement request log created
  -> provider/mock adapter invoked
  -> account/application state updated
  -> webhook event queued
```

### Repayment and Foreclosure Flow

```txt
Payment or foreclosure command
  -> account and schedule loaded
  -> amount/date/status validations
  -> payment allocation or quote calculation
  -> account/installment/quote state updated
  -> closure events and webhooks when settled
```

### Report Flow

```txt
System admin request
  -> synchronous preview/CSV or async report_request insert
  -> scheduled worker claims pending requests
  -> report query and CSV generation
  -> report_content stored
  -> optional email notification sent
```

### Webhook Flow

```txt
Business event
  -> webhook_event_outbox row
  -> scheduled worker or manual dispatch
  -> SSRF-safe URL validation
  -> HMAC signature headers generated
  -> outbound POST
  -> delivery attempt row
  -> outbox status updated with retry/backoff or delivered state
```

## 11. External Integrations

| Integration | Files | Env/config | Usage | Data sent/received | Security notes |
|---|---|---|---|---|---|
| PostgreSQL | `application.yml`, repositories, Flyway migrations, tenant datasource files | `LMS_DB_URL`, `LMS_DB_USERNAME`, `LMS_DB_PASSWORD`, tenant datasource credentials | Main relational database | All application data | Uses RLS for tenant tables; local config contains sensitive-looking values and must be reviewed |
| Redis | `RateLimitConfig`, `RateLimitFilter`, `application.yml` | `LMS_REDIS_HOST`, `LMS_REDIS_PORT`, `app.rate-limit.*` | Bucket4j distributed rate limiting | Rate limit bucket state | If Redis unavailable and rate limiting enabled, behavior should be verified |
| SMTP/mail | `ReportNotificationService`, `application.yml` | `LMS_MAIL_HOST`, `LMS_MAIL_PORT`, report notification config | Report completion/failure notifications | Email recipient, report links/status | Ensure no report content or secrets are leaked in emails |
| R2/S3-compatible storage | `R2LoanDocumentStorageService`, `DocumentStorageService`, `application.yml` | `APP_STORAGE_DOCUMENTS_R2_*`, provider setting | Stores LMS-managed loan documents | Document bytes and metadata | Access/secret keys are sensitive; object keys and bucket permissions need review |
| Local filesystem storage | `FileSystemLoanDocumentStorageService` | `APP_STORAGE_DOCUMENTS_PROVIDER`, `APP_STORAGE_DOCUMENTS_ROOT_PATH` | Local document storage | Document bytes | Path normalization and storage key integrity should be reviewed |
| Outbound webhooks | `WebhookOutboxService`, `HttpWebhookDeliveryClient`, `SsrfSafeUrlValidator` | LSP webhook fields in DB; `app.webhooks.delivery.*` | Sends lifecycle events to LSPs | JSON payloads, HMAC headers, delivery ids | SSRF validator exists; DNS failure/rebinding behavior needs review |
| RabbitMQ | `application.yml`, `pom.xml`, infra config | `LMS_RABBITMQ_*` | Dependency/config present | No active listener/publisher found in inspected runtime code | Needs review. Appears configured but unused |
| MinIO/local S3 infra | Infra/docker config and S3-compatible storage support | S3/R2 storage env properties | Local object storage candidate | Document bytes | Needs explicit provider configuration |

## 12. Environment Variables

Do not expose secret values. The table lists variable names and observed usage.

| Variable name | Where used | Purpose | Required/optional if identifiable | Security notes |
|---|---|---|---|---|
| `LMS_DB_URL` | `application.yml` datasource | PostgreSQL JDBC URL | Required for non-default DB | Sensitive if URL embeds credentials |
| `LMS_DB_USERNAME` | `application.yml` datasource | PostgreSQL app username | Required | Secret-like |
| `LMS_DB_PASSWORD` | `application.yml` datasource | PostgreSQL app password | Required | Secret |
| `APP_TENANT_DATASOURCE_USERNAME` | Tenant datasource config | Tenant/RLS datasource username | Required when tenant datasource override used | Secret-like |
| `APP_TENANT_DATASOURCE_PASSWORD` | Tenant datasource config | Tenant/RLS datasource password | Required when tenant datasource override used | Secret |
| `LMS_REDIS_HOST` | Redis/rate limit config | Redis host | Required if rate limiting enabled | Infrastructure setting |
| `LMS_REDIS_PORT` | Redis/rate limit config | Redis port | Required if rate limiting enabled | Infrastructure setting |
| `LMS_RABBITMQ_HOST` | RabbitMQ config | RabbitMQ host | Optional/unused in inspected code | Needs review |
| `LMS_RABBITMQ_PORT` | RabbitMQ config | RabbitMQ port | Optional/unused in inspected code | Needs review |
| `LMS_RABBITMQ_USERNAME` | RabbitMQ config | RabbitMQ username | Optional/unused in inspected code | Secret-like |
| `LMS_RABBITMQ_PASSWORD` | RabbitMQ config | RabbitMQ password | Optional/unused in inspected code | Secret |
| `LMS_MAIL_HOST` | Mail config | SMTP host | Required if report email enabled | Infrastructure setting |
| `LMS_MAIL_PORT` | Mail config | SMTP port | Required if report email enabled | Infrastructure setting |
| `APP_SECURITY_BOOTSTRAP_USERNAME` | `SecurityProperties`, local bootstrap | Bootstrap admin username | Optional/local bootstrap | Avoid production defaults |
| `APP_SECURITY_BOOTSTRAP_PASSWORD` | `SecurityProperties`, local bootstrap | Bootstrap admin password | Optional/local bootstrap | Secret; local/default values are risky |
| `APP_SECURITY_JWT_SECRET` | JWT config | HS256 signing secret | Required for token signing | Secret; must be strong and environment-specific |
| `APP_SECURITY_JWT_ISSUER` | JWT config | JWT issuer validation | Required | Should match deployment |
| `APP_SECURITY_JWT_TTL` | JWT config | Access token lifetime | Optional with default | Security-sensitive duration |
| `APP_SECURITY_JWT_REFRESH_TTL` | JWT config | Refresh token lifetime | Optional with default | Security-sensitive duration |
| `APP_SECURITY_JWT_SECURE_COOKIES` | JWT cookie config | Sets secure cookie behavior | Required by environment | Must be true behind HTTPS |
| `APP_RATE_LIMIT_ENABLED` | `RateLimitFilter` conditional property | Enables/disables rate limiting | Optional, default behavior depends config | Disabling weakens brute-force protection |
| `APP_RATE_LIMIT_AUTH_PER_MINUTE` | Rate limit properties | Auth endpoint limit | Optional/defaulted | Tune for production |
| `APP_RATE_LIMIT_LSP_WRITE_PER_MINUTE` | Rate limit properties | LSP write endpoint limit | Optional/defaulted | Tune for production |
| `APP_REPORTS_NOTIFICATIONS_ENABLED` | Report notification config | Enables report emails | Optional/defaulted | Avoid unintended emails |
| `APP_REPORTS_NOTIFICATIONS_FROM_ADDRESS` | Report notification config | From address | Required if email enabled | Avoid spoofing/misconfig |
| `APP_REPORTS_NOTIFICATIONS_REPORTS_PAGE_URL` | Report notification config | Link in report emails | Required if email enabled | Must point to trusted UI |
| `APP_REPORTS_PROCESSING_ENABLED` | Report worker config | Enables async report worker | Optional/defaulted | Worker gate |
| `APP_REPORTS_PROCESSING_FIXED_DELAY_MS` | Report worker config | Worker schedule delay | Optional/defaulted | Operational tuning |
| `APP_REPORTS_PROCESSING_BATCH_SIZE` | Report worker config | Worker batch size | Optional/defaulted | Operational tuning |
| `APP_WEBHOOKS_DELIVERY_ENABLED` | Webhook worker config | Enables scheduled webhook delivery | Optional/defaulted | Worker gate |
| `APP_WEBHOOKS_DELIVERY_FIXED_DELAY_MS` | Webhook worker config | Delivery schedule delay | Optional/defaulted | Retry/throughput tuning |
| `APP_WEBHOOKS_DELIVERY_BATCH_SIZE` | Webhook worker config | Dispatch batch size | Optional/defaulted | Throughput tuning |
| `APP_STORAGE_DOCUMENTS_PROVIDER` | Document storage config | Selects local or R2/S3-compatible storage | Required when storing content | Choose deployment-safe provider |
| `APP_STORAGE_DOCUMENTS_ROOT_PATH` | Local document storage | Local root directory | Required for local provider | Protect filesystem path |
| `APP_STORAGE_DOCUMENTS_R2_ENDPOINT` | R2/S3 storage | Object storage endpoint | Required for R2 provider | Trusted endpoint only |
| `APP_STORAGE_DOCUMENTS_R2_ACCESS_KEY` | R2/S3 storage | Access key | Required for R2 provider | Secret |
| `APP_STORAGE_DOCUMENTS_R2_SECRET_KEY` | R2/S3 storage | Secret key | Required for R2 provider | Secret |
| `APP_STORAGE_DOCUMENTS_R2_BUCKET` | R2/S3 storage | Bucket name | Required for R2 provider | Access policy sensitive |
| `APP_STORAGE_DOCUMENTS_R2_REGION` | R2/S3 storage | Region | Required/optional depending provider | Configuration |

## 13. Validation and Middleware

| Area | Implementation | Applied to |
|---|---|---|
| Bean Validation | Request records use annotations such as `@Valid`, `@NotBlank`, `@NotNull`, `@Email`, `@Pattern`, `@Size`, `@DecimalMin`, `@DecimalMax`, `@Min`, `@Max`, `@Past`, `@PastOrPresent`, `@AssertTrue` | Controller request bodies and params |
| Security filter chain | `SecurityConfig` | All HTTP requests |
| JWT auth | Spring Security resource server with custom JWT converter | Authenticated APIs |
| Password-change block | Security authorization rule requiring no `ROLE_PASSWORD_CHANGE_REQUIRED` for most `/api/v1/**` routes | All application APIs except auth/password flow |
| Correlation ID | `CorrelationIdFilter` | All requests |
| Rate limiting | `RateLimitFilter` with Redis/Bucket4j | Auth endpoints and LSP write endpoints |
| LSP IP allowlist | `LspIpAllowlistFilter` | LSP API requests when allowlist rows exist |
| Tenant context | `LspTenantContextInterceptor` | `/api/v1/lsp/**` |
| Tenant datasource/RLS | `TenantAwareDataSource`, `TenantRoutingDataSource`, migrations | Database access for tenant-scoped flows |
| Pagination validation | `PaginationResponseBuilder` plus controller params | List endpoints |
| SSRF validation | `SsrfSafeUrlValidator` | Outbound webhook URLs |
| OpenAPI/Jackson config | `config` package | Serialization/API docs |

Validation gaps requiring review:

| Area | Gap |
|---|---|
| File uploads | No clear file size limit, MIME allowlist, extension allowlist, or malware scanning found |
| Webhook URL DNS behavior | Validator exists, but behavior on DNS failure and DNS rebinding needs review |
| Permissions table | `app_permission` exists but active permission-level validation was not found |

## 14. Error Handling

Global error handling is centralized in `GlobalExceptionHandler` and standard error responses use `ApiError`.

### Error Shape

`ApiError` contains these fields:

| Field | Purpose |
|---|---|
| `timestamp` | Error time |
| `status` | HTTP status |
| `code` | Application/code category |
| `error` | HTTP reason/category |
| `message` | Human-readable message |
| `path` | Request path |
| `correlationId` | Correlation id |
| `errorCode`, `errorReason`, `errorSource` | Additional structured error metadata |
| `violations` | Bean Validation field violations |
| `errors` | Additional error details |

### Handled Cases

| Exception/error | Response behavior |
|---|---|
| Validation failures | Structured validation error with violations |
| Business rule/domain exceptions | Structured error with appropriate status |
| Conflict exceptions | Conflict-style error |
| KYC/document required errors | Structured workflow validation errors |
| Bad credentials | Authentication error |
| Access denied / authorization denied | 403 or password-change-specific handling |
| Unhandled exceptions | Logged with method/URI and stack trace; generic 500 response |

### Gaps/Inconsistencies

| Area | Finding |
|---|---|
| Binary download misses | Some document download paths return empty 404-style responses instead of standard `ApiError` |
| Response wrappers | Some controllers return raw DTOs/binary/CSV while others use envelopes. This is code-supported but not fully uniform |

## 15. Background Jobs / Queues / Workers

| Worker/job | Trigger | Files | Data used | Retry/failure behavior | Notes |
|---|---|---|---|---|---|
| Webhook dispatch worker | `@Scheduled(fixedDelayString="${app.webhooks.delivery.fixed-delay-ms:60000}")` | `WebhookOutboxDispatchWorker`, `WebhookOutboxService`, `HttpWebhookDeliveryClient` | `webhook_event_outbox`, `webhook_event_delivery_attempt`, `lsp` webhook fields | 2xx delivered; 408/429/5xx retryable; other failures permanent; retry delay grows up to a capped delay | Controlled by `app.webhooks.delivery.enabled`; manual dispatch endpoint also exists |
| Report request processor | `@Scheduled(fixedDelayString="${app.reports.processing.fixed-delay-ms:15000}")` | `ReportRequestProcessingWorker`, `ReportRequestService`, `AdminReportingService`, `ReportNotificationService` | `report_request`, reporting queries | Marks requests processing/completed/failed; stores error message; optional notification email | Controlled by `app.reports.processing.enabled` |
| Role bootstrap | Application startup runner | `RoleBootstrapService` | `app_role` | Idempotent seed/update behavior | Seeds known role codes |
| Local bootstrap admin sync | Application startup runner/local config | `LocalBootstrapAdminSyncService` | `app_user`, roles | Syncs configured local/bootstrap admin | Ensure not active with weak defaults in production |
| Sample catalog seed | Application startup runner/property gated | `SampleCatalogSeedService` | LSP/product/mapping tables | Seeds sample catalog data | Controlled by seed property |
| Local demo portfolio seed | Application startup runner/property gated | `LocalDemoPortfolioSeedService` | Borrower/loan/product/account tables | Seeds demo portfolio data | Local/demo only |
| RabbitMQ workers | None found | RabbitMQ dependency/config present | None found | None found | Needs review; queue config exists but no listener/publisher was found in inspected runtime code |

## 16. Module Dependency Map

```txt
AuthController
  -> Spring AuthenticationManager / ApiClientAuthenticationService
  -> JwtTokenService
  -> RefreshTokenRepository
  -> app_user/api_client/refresh_token

LspLoanApplicationApiController
  -> LspAuthenticationSupport
  -> LoanApplicationService
  -> LoanApplicationLifecycleService
  -> LoanDocumentService / LoanRepaymentScheduleService / LoanDisbursementService
  -> repositories
  -> PostgreSQL + optional document storage + webhook outbox

LoanApplicationOpsController
  -> LoanApplicationService
  -> LoanApplicationLifecycleService
  -> LoanDocumentService
  -> LoanDisbursementService
  -> LoanRepaymentCommandService
  -> LoanForeclosureCommandService
  -> repositories
  -> PostgreSQL + storage + webhook outbox

LoanProductAdminController / ProductLspMappingAdminController
  -> ProductConfigurationService
  -> LoanProductRepository / LoanProductLspMappingRepository
  -> loan_product / loan_product_lsp_mapping / loan_product_audit_event

UserAdminController
  -> AdminDirectoryService
  -> AppUserRepository / AppRoleRepository / LspRepository
  -> app_user / app_user_role / app_role

ApiClientAdminController
  -> ApiClientManagementService
  -> ApiClientRepository / LspRepository
  -> api_client

ReportAdminController
  -> AdminReportingService / ReportRequestService
  -> reporting repositories / report_request
  -> optional ReportNotificationService

WebhookOutboxAdminController and WebhookOutboxDispatchWorker
  -> WebhookOutboxService
  -> HttpWebhookDeliveryClient
  -> webhook_event_outbox / webhook_event_delivery_attempt
  -> outbound HTTP webhook endpoint

Security filters
  -> SecurityConfig
  -> JwtTokenService / AppUserDetailsService
  -> RateLimitFilter / LspIpAllowlistFilter
  -> Redis / database

Tenant isolation
  -> LspTenantContextInterceptor
  -> TenantDataAccessContextHolder
  -> TenantAwareDataSource
  -> PostgreSQL RLS policies
```

## 17. API Response Standards

| Response type | Observed standard |
|---|---|
| Auth success | `TokenResponse` with `accessToken`, `tokenType`, `expiresInSeconds`, and `passwordChangeRequired` where relevant; refresh token set as HttpOnly cookie |
| Standard JSON success | Many endpoints return DTO records directly or inside `ApiEnvelope`/pagination helper structures |
| List/pagination | List endpoints commonly support offset/limit and `paginationDetails`; helper can include or omit pagination details |
| CSV/report download | Report export returns `text/csv` |
| Binary document download | Document endpoints return binary/ZIP content with content headers when found |
| Validation error | `ApiError` with `violations` |
| Auth error | `ApiError` from security entry point or exception handler |
| Password-change required | Authorization response uses password-change-specific status/error handling |
| Generic server error | `ApiError` with generic 500 message and correlation id |

Inconsistencies:

| Area | Note |
|---|---|
| Envelope usage | Not every success response is wrapped in the same envelope |
| Download not found | Some binary download misses return empty 404 instead of `ApiError` |
| Admin secret responses | Some admin responses intentionally include one-time or sensitive values, such as API client secret once, temporary password, and webhook signing secret |

## 18. Security Review Notes

These are code-supported observations only.

| Area | Observation |
|---|---|
| Hardcoded/default secrets | `application-local.yml` and `SecurityProperties` include local/default sensitive-looking credentials and JWT/bootstrap defaults. Ensure no real production secrets are committed or deployed |
| JWT secret | HS256 signing is used. `APP_SECURITY_JWT_SECRET` must be strong, private, and environment-specific |
| Refresh cookie and CSRF | CSRF is disabled while refresh/logout use a cookie. Cookie is HttpOnly and SameSite Strict, with secure flag configurable. Browser threat model needs review |
| CORS | CORS allows credentials and configured origins. Localhost origins appear in config. Production origins need review |
| LSP webhook signing secret | Stored in `lsp.webhook_signing_secret` and returned to system admin in webhook subscription response. Treat as highly sensitive |
| API client secret | Secret is returned once on create and stored as bcrypt hash. Admin UI/logging must avoid leaking it |
| Temporary passwords | Reset endpoint returns temporary password to `SYSTEM_ADMIN`. Operational handling must be controlled |
| File uploads | No clear upload size limit, content-type allowlist, extension allowlist, or malware scan was found |
| Local document retrieval | Storage keys are generated internally, but local retrieval path handling should be reviewed for path traversal if a storage key can be tampered in DB |
| Tenant isolation | LSP data isolation depends on JWT LSP claims, interceptor-set tenant context, service checks, and PostgreSQL RLS. Any LSP path that bypasses these would be high risk; tests exist but route coverage should be reviewed |
| IP allowlist behavior | Empty allowlist means allow all for an LSP. This may be intentional but should be operationally documented |
| Rate limiting | Rate limiting depends on Redis/Bucket4j and can be disabled by property. Production should verify it is enabled and sized correctly |
| SSRF protection | `SsrfSafeUrlValidator` checks outbound webhook URLs and private/reserved addresses. DNS failure/rebinding and multi-address behavior need review |
| Query injection | Inspected repository usage appears to rely on JPA/parameterized queries. No string-concatenated SQL risk was identified in the summarized surfaces |
| Sensitive audit/report data | Intake payloads, reports, borrower PII reveal audit, document access audit, and report content can contain sensitive data. Retention and access should be reviewed |
| Mock disbursement | Mock disbursement outcome endpoint is admin-only. Ensure it is disabled or tightly controlled in production if not intended |

## 19. Known Gaps / Needs Review

| Gap | Why it needs review | Recommended next step |
|---|---|---|
| Permission tables vs role checks | `app_permission` and `app_role_permission` exist, but observed authorization is role-based | Confirm whether permission-level authorization is planned, unused, or incomplete |
| Generated Graphify output under source tree | `backend/src/main/java/com/bhawana/graphify-out` appears generated/non-runtime | Move generated artifacts outside runtime source if not needed |
| Production secret posture | Local/default config contains sensitive-looking values | Move all secrets to environment/secret manager and scrub committed config |
| File upload controls | Upload endpoints store files without clear size/type/malware controls | Add or document maximum size, content validation, extension allowlist, and scanning strategy |
| Webhook SSRF edge cases | Validator exists but DNS failure/rebinding behavior needs deeper review | Add tests for private IP DNS, multiple A/AAAA records, redirects, and DNS rebinding |
| RabbitMQ usage | Dependency/config exists but no active runtime usage found | Remove unused config/dependency or document planned queue usage |
| Refresh-cookie CSRF model | CSRF disabled with cookie-based refresh/logout | Confirm SameSite/secure-cookie assumptions for all deployed clients |
| Response standard consistency | Success responses are not uniformly enveloped; some binary 404s bypass `ApiError` | Decide and document API response standard |
| RLS route coverage | Tenant isolation is strong but depends on interceptor and datasource mode | Add/verify tests for every `/api/v1/lsp/**` route and repository path |
| Webhook signing secret exposure | Admin response includes signing secret | Decide whether to return only masked value after initial set |
| Report content storage | Full report content is stored in `report_request` | Define retention, encryption, and access policy |
| Bootstrap admin defaults | Bootstrap properties can create/sync admin accounts | Ensure disabled or secured outside local development |
