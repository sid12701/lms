package com.bhawana.lms.config;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.security.RateLimitProperties;
import com.bhawana.lms.security.RateLimitRule;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.util.AntPathMatcher;

/**
 * M13: enriches the generated OpenAPI documents with the integration contract the
 * annotations alone cannot express — the {@code bearerAuth} security requirement per
 * operation, the {@link ApiError} schema, meaningful per-route error responses with
 * representative examples, idempotency/pagination/rate-limit headers, and operation
 * summaries.
 *
 * <p>Implemented as a {@link GlobalOpenApiCustomizer} so the same contract applies to
 * the default document (exported to {@code openapi/openapi.json} for the frontend type
 * pipeline) and to the {@code partner} group at {@code /v3/api-docs/partner}. Route
 * tables below are keyed {@code "METHOD /path"} exactly as the generated paths appear.
 *
 * <p>Error responses are attached by convention, not blanket: every operation gets the
 * codes it can genuinely emit (401/403 by security posture, 400 by declared inputs, 404
 * by path variables, 409 by idempotency-key usage, 410/422 only where the route's own
 * contract produces them, 429 by the configured rate-limit rules).
 */
public class OpenApiContractCustomizer implements GlobalOpenApiCustomizer {

    static final String BEARER_AUTH = "bearerAuth";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private static final String API_ERROR_REF = "#/components/schemas/ApiError";

    /**
     * permitAll routes in {@code SecurityFilterChainConfig}: credential issuance,
     * refresh rotation, and logout. Every other documented route requires a bearer
     * token. Keep in sync with the security filter chain's permitAll list.
     */
    private static final Set<String> PUBLIC_ROUTES = Set.of(
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/token",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout"
    );

    /**
     * Routes that reject a missing {@code Idempotency-Key} outright (400
     * INVALID_REQUEST). All other key-aware routes treat the header as optional and
     * fall back to the legacy non-idempotent path when it is absent.
     */
    private static final Set<String> IDEMPOTENCY_KEY_REQUIRED_ROUTES = Set.of(
            "POST /api/v1/lsp/loans/{loanId}/payments",
            "POST /api/v1/lsp/loan-applications/{applicationId}/invalid",
            "POST /api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute"
    );

    /** Route -> representative 422 business-rule error code. */
    private static final Map<String, String> UNPROCESSABLE_ROUTES = Map.ofEntries(
            Map.entry("GET /api/v1/lsp/loan-applications", "INVALID_STATUS"),
            Map.entry("POST /api/v1/lsp/loan-applications", "PRODUCT_NOT_MAPPED"),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/invalid", "INVALIDATION_NOT_ALLOWED"),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/documents", "DOCUMENT_MIME_NOT_ALLOWED"),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/documents/batch", "DOCUMENT_MIME_NOT_ALLOWED"),
            Map.entry("PUT /api/v1/lsp/loan-applications/{applicationId}/repayment-schedule", "REPAYMENT_SCHEDULE_INVALID"),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check", "DISBURSEMENT_VALIDATION_FAILED"),
            Map.entry("GET /api/v1/lsp/loan-events", "INVALID_CURSOR"),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/payments", "REPAYMENT_NOT_ALLOWED"),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/foreclosure-quote", "FORECLOSURE_QUOTE_DATE_INVALID"),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute", "FORECLOSURE_QUOTE_STALE"),
            Map.entry("PATCH /api/v1/lsp/borrowers/{borrowerId}/bank-details", "BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT")
    );

    private static final Map<String, String> OPERATION_SUMMARIES = Map.ofEntries(
            Map.entry("GET /api/v1/lsp/loan-applications", "List the caller's loan applications"),
            Map.entry("POST /api/v1/lsp/loan-applications", "Create a loan application"),
            Map.entry("GET /api/v1/lsp/loan-applications/invalid-reasons", "List the allowed invalid-loan reason codes"),
            Map.entry("GET /api/v1/lsp/loan-applications/document-requirements", "List document requirements by document type"),
            Map.entry("GET /api/v1/lsp/loan-applications/{applicationId}", "Get loan application detail"),
            Map.entry("GET /api/v1/lsp/loan-applications/external/{externalLoanId}", "Get an application by the LSP's external loan id"),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/invalid", "Mark a loan application invalid"),
            Map.entry("GET /api/v1/lsp/loan-applications/{applicationId}/documents", "List submitted documents for an application"),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/documents", "Submit document metadata or upload a document file"),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/documents/batch", "Upload multiple documents in one request"),
            Map.entry("PUT /api/v1/lsp/loan-applications/{applicationId}/repayment-schedule", "Replace the pre-disbursement repayment schedule"),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check", "Verify disbursement bank details (read-only preflight)"),
            Map.entry("GET /api/v1/lsp/loan-events", "Read the loan event feed from a cursor"),
            Map.entry("GET /api/v1/lsp/loans/{loanId}", "Get loan detail"),
            Map.entry("GET /api/v1/lsp/loans/{loanId}/repayment-schedule", "List a loan's repayment schedule"),
            Map.entry("GET /api/v1/lsp/loans/{loanId}/payments", "List a loan's payment transactions (bounded page)"),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/payments", "Record a borrower payment"),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/foreclosure-quote", "Request a foreclosure quote"),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute", "Execute a foreclosure quote to settle the loan"),
            Map.entry("GET /api/v1/lsp/borrowers/{borrowerId}/bank-details", "Get a borrower's bank details (unmasked, audited)"),
            Map.entry("PATCH /api/v1/lsp/borrowers/{borrowerId}/bank-details", "Update a borrower's bank details"),
            Map.entry("GET /api/v1/lsp/products", "List products provisioned to the caller"),
            Map.entry("POST /api/v1/auth/login", "Log in with email and password (human users)"),
            Map.entry("POST /api/v1/auth/token", "Issue an access token for an API client"),
            Map.entry("POST /api/v1/auth/refresh", "Rotate the refresh cookie and issue a new access token"),
            Map.entry("POST /api/v1/auth/password", "Change the authenticated user's password"),
            Map.entry("POST /api/v1/auth/logout", "Log out and revoke the refresh-token family")
    );

    private static final Map<String, String> OPERATION_DESCRIPTIONS = Map.ofEntries(
            Map.entry("GET /api/v1/lsp/loan-applications",
                    "Filters: productId, status, sourceChannel, free-text q. An unrecognized status "
                            + "returns 422 INVALID_STATUS. The body is always a raw array; the applied "
                            + "window is disclosed via the X-Limit/X-Offset headers, and "
                            + "paginationDetails=ON adds X-Total-Count."),
            Map.entry("POST /api/v1/lsp/loan-applications",
                    "lspId must match the authenticated LSP (403 otherwise). Reusing a lspLoanId "
                            + "returns 409 DUPLICATE_EXTERNAL_LOAN_ID. Sending an Idempotency-Key makes "
                            + "the request replayable."),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/invalid",
                    "reasonCode must come from GET /api/v1/lsp/loan-applications/invalid-reasons; "
                            + "reasonText is required only for OTHERS. Idempotency-Key is required."),
            Map.entry("POST /api/v1/lsp/loan-applications/{applicationId}/documents",
                    "Two variants share this route: application/json submits document metadata; "
                            + "multipart/form-data uploads the file itself."),
            Map.entry("PUT /api/v1/lsp/loan-applications/{applicationId}/repayment-schedule",
                    "Full replacement of the pre-disbursement schedule — naturally idempotent, no "
                            + "Idempotency-Key needed. mode=LSP_PROVIDED rows must pass principal "
                            + "integrity plus date/interest discipline; violations return 422 "
                            + "REPAYMENT_SCHEDULE_INVALID."),
            Map.entry("GET /api/v1/lsp/loan-events",
                    "At-least-once delivery — dedupe on eventId. Ordering is per loan, not global. "
                            + "The cursor is opaque: store nextCursor and hand it back. A cursor older "
                            + "than the retained window (at least 30 days) returns 410 CURSOR_EXPIRED; "
                            + "resync via GET /api/v1/lsp/loan-applications and resume with no cursor."),
            Map.entry("GET /api/v1/lsp/loans/{loanId}/payments",
                    "Always a bounded page: the body is a raw array, X-Limit/X-Offset are always "
                            + "emitted, and paginationDetails=ON adds X-Total-Count. Omitting the "
                            + "parameters returns the first page (default limit 50)."),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/payments",
                    "Records a repayment allocated against the target installment. "
                            + "Idempotency-Key is required."),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/foreclosure-quote",
                    "The quote is redeemable only on effectiveDate, which must be the current "
                            + "business date (422 FORECLOSURE_QUOTE_DATE_INVALID). A new quote "
                            + "supersedes the previous ACTIVE one. Idempotency-Key is optional: a "
                            + "keyed retry returns the stored quote."),
            Map.entry("POST /api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                    "Redeems the quote with a settlement receipt. If receipts or the schedule changed "
                            + "since the quote was issued, the quote is stale and execution returns 422 "
                            + "FORECLOSURE_QUOTE_STALE — request a fresh quote. Idempotency-Key is "
                            + "required."),
            Map.entry("GET /api/v1/lsp/borrowers/{borrowerId}/bank-details",
                    "The one LSP read that returns the full unmasked bank account number; every read "
                            + "writes a PII-reveal audit entry."),
            Map.entry("PATCH /api/v1/lsp/borrowers/{borrowerId}/bank-details",
                    "State overwrite — a no-op resubmission writes nothing, so no Idempotency-Key is "
                            + "needed. Locked while a disbursement is in flight (422 "
                            + "BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT)."),
            Map.entry("POST /api/v1/auth/token",
                    "Client-credentials issuance for machine API clients; the returned JWT carries "
                            + "the roles used by the /api/v1/lsp/** routes.")
    );

    /** Routes whose 200 responses carry a representative inline example. */
    private static final Map<String, Object> SUCCESS_EXAMPLES = Map.of(
            "POST /api/v1/auth/token", orderedMap(
                    "accessToken", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJsc3AtY2xpZW50LTEiLCJhdWQiOiJtYWNoaW5lIn0.signature",
                    "tokenType", "Bearer",
                    "expiresInSeconds", 3600,
                    "passwordChangeRequired", false),
            "GET /api/v1/lsp/products", List.of(orderedMap(
                    "id", "8c2f7d2e-9f42-4f58-bb1a-0f9d2f4a0e11",
                    "code", "PL-STANDARD",
                    "name", "Standard Personal Loan",
                    "minPrincipal", "10000.00",
                    "maxPrincipal", "500000.00",
                    "interestRate", "18.50",
                    "processingFeeRate", "2.00",
                    "minTenureMonths", 6,
                    "maxTenureMonths", 36,
                    "status", "ACTIVE")),
            "GET /api/v1/lsp/loan-events", orderedMap(
                    "items", List.of(orderedMap(
                            "eventId", "1f6f4b0a-2a6c-4a4b-9d8e-0a1f3c2d4e5f",
                            "schemaVersion", 1,
                            "eventType", "LOAN_DISBURSED",
                            "occurredAt", "2026-09-21T09:14:03Z",
                            "aggregateType", "LOAN_ACCOUNT",
                            "aggregateId", "la_9f2c",
                            "lspId", "8c2f7d2e-9f42-4f58-bb1a-0f9d2f4a0e11",
                            "lspCode", "ACME",
                            "loanApplicationId", "5b3e1a0d-7d2f-4c8a-b6e1-2a3c4d5e6f70",
                            "correlationId", "b3f1c2d4-1a2b-4c3d-8e9f-0a1b2c3d4e5f",
                            "payload", orderedMap("status", "DISBURSED"))),
                    "nextCursor", "eyJsYXN0RXZlbnRJZCI6MTAyNH0",
                    "limit", 100,
                    "hasMore", true),
            "POST /api/v1/lsp/loans/{loanId}/foreclosure-quote", orderedMap(
                    "id", "6d5c4b3a-2f1e-4a9b-8c7d-6e5f4a3b2c1d",
                    "loanAccountId", "5b3e1a0d-7d2f-4c8a-b6e1-2a3c4d5e6f70",
                    "version", 1,
                    "requestedByUsername", "acme-api-client",
                    "executedByUsername", null,
                    "effectiveDate", "2026-09-22",
                    "outstandingPrincipal", "182500.00",
                    "outstandingInterest", "4120.55",
                    "settlementAmount", "186620.55",
                    "status", "ACTIVE",
                    "executedAt", null,
                    "createdAt", "2026-09-22T08:30:00Z",
                    "updatedAt", "2026-09-22T08:30:00Z")
    );

    private final RateLimitProperties rateLimitProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public OpenApiContractCustomizer(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            return;
        }
        ensureApiErrorSchema(openApi);
        openApi.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap()
                .forEach((method, operation) -> customiseOperation(path, method.name(), operation)));
    }

    /**
     * Group-level customizer for the partner document: retitles the shared info block
     * so readers landing on {@code /v3/api-docs/partner} see the partner surface, not
     * the internal one.
     */
    static OpenApiCustomizer partnerInfoCustomizer() {
        return openApi -> {
            if (openApi.getInfo() != null) {
                openApi.getInfo()
                        .title("Bhawana LMS Partner API")
                        .description("LSP integration surface: loan applications, servicing, the "
                                + "loan event feed, and client credential issuance. Internal admin "
                                + "operations are excluded from this document.");
            }
        };
    }

    private void customiseOperation(String path, String httpMethod, Operation operation) {
        String route = httpMethod + " " + path;
        boolean publicRoute = PUBLIC_ROUTES.contains(route);

        if (!publicRoute && (operation.getSecurity() == null || operation.getSecurity().isEmpty())) {
            operation.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
        }
        if (operation.getSummary() == null || operation.getSummary().isBlank()) {
            operation.setSummary(OPERATION_SUMMARIES.getOrDefault(route, deriveSummary(operation.getOperationId())));
        }
        if (operation.getDescription() == null || operation.getDescription().isBlank()) {
            String description = OPERATION_DESCRIPTIONS.get(route);
            if (description != null) {
                operation.setDescription(description);
            }
        }
        String tag = partnerTag(path);
        if (tag != null) {
            operation.setTags(List.of(tag));
        }

        List<Parameter> parameters = operation.getParameters();
        if (parameters != null) {
            for (Parameter parameter : parameters) {
                customiseParameter(route, parameter);
            }
        }

        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }
        boolean hasInputs = operation.getRequestBody() != null
                || (parameters != null && !parameters.isEmpty());
        if (hasInputs) {
            addErrorResponse(responses, "400",
                    "The request failed validation (VALIDATION_FAILED) or could not be parsed "
                            + "(INVALID_REQUEST). Field-level failures are listed in violations[] "
                            + "and errors[].",
                    apiErrorExample(400, "VALIDATION_FAILED", "Request validation failed", path,
                            orderedMap("field", "request", "message", "This field is required.")));
        }
        if (publicRoute) {
            addPublicAuthErrorResponses(route, path, responses);
        } else {
            addErrorResponse(responses, "401",
                    "Missing, expired, or invalid bearer token (UNAUTHORIZED).",
                    apiErrorExample(401, "UNAUTHORIZED",
                            "Authentication is required to access this resource.", path, null));
            addErrorResponse(responses, "403",
                    "The caller lacks the required role (ACCESS_DENIED). On the /api/v1/lsp surface "
                            + "this is also returned when the source IP is outside the LSP's "
                            + "allowlist (LSP_SURFACE_IP_ACCESS_DENIED).",
                    apiErrorExample(403, "ACCESS_DENIED", "Access denied", path, null));
        }
        if (path.contains("{")) {
            addErrorResponse(responses, "404",
                    "The addressed resource does not exist or is not visible to the caller (NOT_FOUND).",
                    apiErrorExample(404, "NOT_FOUND", "Resource not found", path, null));
        }
        boolean keyGuarded = hasIdempotencyKeyParameter(parameters);
        if (keyGuarded || "POST /api/v1/lsp/loan-applications".equals(route)) {
            ApiResponse conflict = apiErrorResponse(
                    "The request conflicts with existing state. Idempotent endpoints return "
                            + "IDEMPOTENCY_CONFLICT (key reused with a different payload), "
                            + "IDEMPOTENCY_IN_PROGRESS (an identical request is still processing — "
                            + "retry after the Retry-After hint), or IDEMPOTENCY_RECOVERY_REQUIRED "
                            + "(the prior outcome cannot be reconstructed — escalate for "
                            + "reconciliation). Application create can also return "
                            + "DUPLICATE_EXTERNAL_LOAN_ID or BORROWER_PAN_CONFLICT.",
                    apiErrorExample(409, "IDEMPOTENCY_CONFLICT",
                            "Idempotency-Key has already been used for a different request.", path, null));
            conflict.addHeaderObject(RETRY_AFTER_HEADER, retryAfterHeader());
            addErrorResponse(responses, "409", conflict);
        }
        if ("GET /api/v1/lsp/loan-events".equals(route)) {
            addErrorResponse(responses, "410",
                    "The supplied cursor predates the oldest event still retained (CURSOR_EXPIRED). "
                            + "Resync via GET /api/v1/lsp/loan-applications, then resume the feed "
                            + "with no cursor.",
                    apiErrorExample(410, "CURSOR_EXPIRED",
                            "Cursor is older than the retained event window; resync required.", path, null));
        }
        String businessRuleCode = UNPROCESSABLE_ROUTES.get(route);
        if (businessRuleCode != null) {
            addErrorResponse(responses, "422",
                    "A business rule rejected the request (code " + businessRuleCode + " shown; the "
                            + "route's domain rules define the full set).",
                    apiErrorExample(422, businessRuleCode,
                            "Business rule violation: " + businessRuleCode, path, null));
        }
        if (isRateLimited(path, httpMethod)) {
            ApiResponse tooMany = apiErrorResponse(
                    "The configured rate limit for this route was exceeded (RATE_LIMIT_EXCEEDED). "
                            + "Retry after the number of seconds in Retry-After.",
                    apiErrorExample(429, "RATE_LIMIT_EXCEEDED",
                            "Too many requests. Please retry.", path, null));
            tooMany.addHeaderObject(RETRY_AFTER_HEADER, retryAfterHeader());
            addErrorResponse(responses, "429", tooMany);
        }
        if (hasParameter(parameters, "paginationDetails")) {
            ApiResponse ok = responses.get("200");
            if (ok != null) {
                ok.addHeaderObject("X-Limit", intHeader("The page size actually applied."));
                ok.addHeaderObject("X-Offset", intHeader("The zero-based offset actually applied."));
                ok.addHeaderObject("X-Total-Count",
                        intHeader("Total matching rows; emitted only when paginationDetails=ON."));
            }
        }
        Object successExample = SUCCESS_EXAMPLES.get(route);
        ApiResponse success = responses.get("200");
        if (successExample != null && success != null && success.getContent() != null) {
            success.getContent().values().forEach(mediaType -> mediaType.setExample(successExample));
        }
        // springdoc renders ResponseEntity<Void> as 200; logout actually answers 204.
        if ("POST /api/v1/auth/logout".equals(route) && responses.containsKey("200")) {
            responses.remove("200");
            responses.addApiResponse("204", new ApiResponse()
                    .description("Logged out — the refresh-token family is revoked and the cookie "
                            + "cleared."));
        }
    }

    private void customiseParameter(String route, Parameter parameter) {
        if (parameter.getName() == null || parameter.get$ref() != null) {
            return;
        }
        switch (parameter.getName()) {
            case IDEMPOTENCY_KEY_HEADER -> {
                boolean required = IDEMPOTENCY_KEY_REQUIRED_ROUTES.contains(route);
                parameter.setRequired(required);
                parameter.setDescription(required
                        ? "UUID v4 idempotency key (required — a missing or malformed key is rejected "
                                + "with 400 INVALID_REQUEST). A retry with the same key and an identical "
                                + "request returns the stored response; the same key with a different "
                                + "request returns 409 IDEMPOTENCY_CONFLICT."
                        : "Optional UUID v4 idempotency key. When sent, a retry with the same key and an "
                                + "identical request returns the stored response; the same key with a "
                                + "different request returns 409 IDEMPOTENCY_CONFLICT.");
                parameter.setExample("3fa85f64-5717-4562-b3fc-2c963f66afa6");
            }
            case "offset" -> parameter.setDescription("Zero-based row offset (default 0).");
            case "limit" -> parameter.setDescription(
                    "Page size (1-200). Defaults to 50 once any pagination parameter is sent.");
            case "paginationDetails" -> parameter.setDescription(
                    "ON adds the X-Total-Count response header (an extra count query); OFF or omitted "
                            + "suppresses it.");
            case "cursor" -> parameter.setDescription(
                    "Opaque feed cursor returned as nextCursor; omit to start at the beginning of the "
                            + "retained window. A cursor older than the retained log returns 410 "
                            + "CURSOR_EXPIRED.");
            case "eventTypes" -> parameter.setDescription(
                    "Narrows the returned events, never the cursor — cursors always cover the "
                            + "unfiltered stream.");
            case "status" -> {
                if ("GET /api/v1/lsp/loan-applications".equals(route)) {
                    parameter.setDescription(
                            "Loan application status filter; an unrecognized value returns 422 "
                                    + "INVALID_STATUS.");
                }
            }
            default -> {
            }
        }
    }

    /**
     * 401 semantics for the public auth routes: credentials (not tokens) are what
     * fails here. {@code /refresh} answers a distinct {@code {code, message}} body, so
     * its 401 is documented with that inline shape rather than ApiError.
     * {@code /logout} always answers 204 — no error response is documented for it.
     */
    private void addPublicAuthErrorResponses(String route, String path, ApiResponses responses) {
        if ("POST /api/v1/auth/logout".equals(route)) {
            return;
        }
        if ("POST /api/v1/auth/refresh".equals(route)) {
            ApiResponse refreshFailure = new ApiResponse().description(
                    "The refresh cookie is missing, expired, revoked, or already rotated. The body is "
                            + "a small {code, message} object (e.g. MISSING_REFRESH_COOKIE, "
                            + "TOKEN_EXPIRED, TOKEN_REVOKED, TOKEN_ROTATED), not an ApiError.");
            ObjectSchema schema = new ObjectSchema();
            schema.addProperty("code", new StringSchema());
            schema.addProperty("message", new StringSchema());
            MediaType mediaType = new MediaType().schema(schema);
            mediaType.setExample(orderedMap(
                    "code", "MISSING_REFRESH_COOKIE",
                    "message", "Refresh cookie is missing"));
            refreshFailure.setContent(new Content().addMediaType("application/json", mediaType));
            responses.addApiResponse("401", refreshFailure);
            return;
        }
        addErrorResponse(responses, "401",
                "The supplied credentials were rejected (INVALID_CREDENTIALS, ACCOUNT_LOCKED, "
                        + "ACCOUNT_DISABLED, LSP_INACTIVE).",
                apiErrorExample(401, "INVALID_CREDENTIALS", "Invalid credentials", path, null));
    }

    private void ensureApiErrorSchema(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        if (openApi.getComponents().getSchemas() == null) {
            openApi.getComponents().setSchemas(new LinkedHashMap<>());
        }
        if (openApi.getComponents().getSchemas().containsKey("ApiError")) {
            return;
        }
        // Resolve the real record shape so the documented schema drifts with the
        // code. The nested violation/detail records are resolved explicitly — read()
        // only guarantees the root schema, while ApiError's arrays $ref the nested
        // ones by simple name.
        ModelConverters converters = ModelConverters.getInstance();
        Map<String, Schema> resolved = new LinkedHashMap<>(converters.read(ApiError.class));
        resolved.putAll(converters.read(ApiError.FieldViolation.class));
        resolved.putAll(converters.read(ApiError.ErrorDetail.class));
        resolved.forEach((name, schema) -> openApi.getComponents().getSchemas().putIfAbsent(name, schema));
    }

    /**
     * Mirrors {@code RateLimitRuleMatcher} (package-private in the security package):
     * a route is rate-limited when a configured rule's ant-style path pattern and
     * method list both match. Driven by the bound properties so the document tracks
     * the deployed rules automatically.
     */
    private boolean isRateLimited(String path, String httpMethod) {
        if (rateLimitProperties == null) {
            return false;
        }
        for (RateLimitRule rule : rateLimitProperties.getRules()) {
            if (rule.getPath() == null || rule.getPath().isBlank()
                    || rule.getMethods() == null || rule.getMethods().isEmpty()) {
                continue;
            }
            boolean methodMatches = rule.getMethods().stream()
                    .anyMatch(candidate -> candidate != null && candidate.equalsIgnoreCase(httpMethod));
            if (methodMatches && pathMatcher.match(rule.getPath(), path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIdempotencyKeyParameter(List<Parameter> parameters) {
        return hasParameter(parameters, IDEMPOTENCY_KEY_HEADER);
    }

    private static boolean hasParameter(List<Parameter> parameters, String name) {
        if (parameters == null) {
            return false;
        }
        return parameters.stream().anyMatch(parameter -> name.equals(parameter.getName()));
    }

    private static void addErrorResponse(ApiResponses responses, String status, ApiResponse response) {
        if (!responses.containsKey(status)) {
            responses.addApiResponse(status, response);
        }
    }

    private static void addErrorResponse(
            ApiResponses responses,
            String status,
            String description,
            Map<String, Object> example
    ) {
        addErrorResponse(responses, status, apiErrorResponse(description, example));
    }

    private static ApiResponse apiErrorResponse(String description, Map<String, Object> example) {
        MediaType mediaType = new MediaType().schema(new Schema<>().$ref(API_ERROR_REF));
        mediaType.setExample(example);
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json", mediaType));
    }

    private static Header retryAfterHeader() {
        return new Header()
                .description("Seconds to wait before retrying.")
                .schema(new IntegerSchema());
    }

    private static Header intHeader(String description) {
        return new Header().description(description).schema(new IntegerSchema());
    }

    private static String partnerTag(String path) {
        if (path.startsWith("/api/v1/lsp/loan-applications")) {
            return "Loan Applications";
        }
        if (path.startsWith("/api/v1/lsp/loans")) {
            return "Loans";
        }
        if (path.startsWith("/api/v1/lsp/loan-events")) {
            return "Loan Events";
        }
        if (path.startsWith("/api/v1/lsp/borrowers")) {
            return "Borrowers";
        }
        if (path.startsWith("/api/v1/lsp/products")) {
            return "Products";
        }
        if (path.startsWith("/api/v1/auth")) {
            return "Authentication";
        }
        return null;
    }

    private static String deriveSummary(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return null;
        }
        String spaced = operationId.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** The wire shape of {@link ApiError} — a representative instance per status. */
    private static Map<String, Object> apiErrorExample(
            int status,
            String code,
            String message,
            String path,
            Map<String, Object> violation
    ) {
        List<Map<String, Object>> violations = violation == null ? List.of() : List.of(violation);
        List<Map<String, Object>> errors = violation == null
                ? List.of(orderedMap(
                        "errorCode", code,
                        "errorReason", code,
                        "errorSource", message,
                        "field", null,
                        "message", message))
                : List.of(orderedMap(
                        "errorCode", code,
                        "errorReason", code,
                        "errorSource", violation.get("field") + ": " + violation.get("message"),
                        "field", violation.get("field"),
                        "message", violation.get("message")));
        return orderedMap(
                "timestamp", "2026-09-22T10:15:30Z",
                "status", status,
                "code", code,
                "error", code,
                "message", message,
                "path", path,
                "correlationId", "b3f1c2d4-1a2b-4c3d-8e9f-0a1b2c3d4e5f",
                "errorCode", code,
                "errorReason", code,
                "errorSource", message,
                "violations", violations,
                "errors", errors);
    }

    private static Map<String, Object> orderedMap(Object... alternatingKeysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < alternatingKeysAndValues.length; index += 2) {
            map.put((String) alternatingKeysAndValues[index], alternatingKeysAndValues[index + 1]);
        }
        return map;
    }
}
