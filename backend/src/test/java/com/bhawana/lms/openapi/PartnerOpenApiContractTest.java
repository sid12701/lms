package com.bhawana.lms.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract tests for the partner-only document at {@code /v3/api-docs/partner} (M13).
 * Asserts the integration behavior partners depend on: per-operation bearer
 * security, the shared ApiError schema, route-meaningful error/response metadata
 * (idempotency, pagination, cursor expiry, rate limiting), representative examples,
 * and the absence of internal admin operations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=false"
})
class PartnerOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void partnerGroupRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs/partner"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void partnerGroupContainsOnlyLspAndAuthRoutes() throws Exception {
        JsonNode doc = partnerDoc();
        List<String> paths = new ArrayList<>();
        doc.path("paths").fieldNames().forEachRemaining(paths::add);

        assertThat(paths).isNotEmpty();
        assertThat(paths).allSatisfy(path -> assertThat(path)
                .startsWith("/api/v1/")
                .satisfies(p -> assertThat(p).doesNotStartWith("/api/v1/internal")));
        assertThat(paths).contains(
                "/api/v1/lsp/loan-applications",
                "/api/v1/lsp/loans/{loanId}/payments",
                "/api/v1/lsp/loan-events",
                "/api/v1/lsp/borrowers/{borrowerId}/bank-details",
                "/api/v1/lsp/products",
                "/api/v1/auth/token",
                "/api/v1/auth/login");
    }

    @Test
    void protectedOperationsDeclareBearerAuthAndPublicAuthOpsDoNot() throws Exception {
        JsonNode doc = partnerDoc();
        forEachOperation(doc, (path, method, operation) -> {
            boolean publicRoute = isPublicAuthRoute(path);
            JsonNode security = operation.path("security");
            if (publicRoute) {
                assertThat(security.isMissingNode() || security.isEmpty())
                        .as("%s %s must not declare a security requirement", method, path)
                        .isTrue();
            } else {
                assertThat(security.toString())
                        .as("%s %s must declare bearerAuth", method, path)
                        .contains("bearerAuth");
            }
        });
    }

    @Test
    void apiErrorSchemaIsPresentInComponents() throws Exception {
        JsonNode doc = partnerDoc();
        JsonNode apiError = doc.path("components").path("schemas").path("ApiError");
        assertThat(apiError.isMissingNode()).as("components.schemas.ApiError").isFalse();
        JsonNode properties = apiError.path("properties");
        for (String field : List.of(
                "status", "code", "message", "path", "correlationId", "violations", "errors")) {
            assertThat(properties.has(field))
                    .as("ApiError.%s", field)
                    .isTrue();
        }
    }

    @Test
    void idempotencyKeyHeaderIsDocumentedWithCorrectRequiredness() throws Exception {
        JsonNode doc = partnerDoc();
        assertThat(idempotencyKeyParam(doc, "/api/v1/lsp/loans/{loanId}/payments", "post").path("required").asBoolean())
                .as("POST payments Idempotency-Key is required")
                .isTrue();
        assertThat(idempotencyKeyParam(doc, "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute", "post")
                .path("required").asBoolean())
                .as("POST execute Idempotency-Key is required")
                .isTrue();
        assertThat(idempotencyKeyParam(doc, "/api/v1/lsp/loan-applications/{applicationId}/invalid", "post")
                .path("required").asBoolean())
                .as("POST invalid Idempotency-Key is required")
                .isTrue();
        assertThat(idempotencyKeyParam(doc, "/api/v1/lsp/loan-applications", "post").path("required").asBoolean())
                .as("POST loan-applications Idempotency-Key is optional")
                .isFalse();
        assertThat(idempotencyKeyParam(doc, "/api/v1/lsp/loans/{loanId}/foreclosure-quote", "post")
                .path("required").asBoolean())
                .as("POST foreclosure-quote Idempotency-Key is optional")
                .isFalse();
    }

    @Test
    void conflictAndRetryAfterAreDocumentedOnIdempotentOps() throws Exception {
        JsonNode doc = partnerDoc();
        JsonNode conflict = doc.at("/paths/~1api~1v1~1lsp~1loans~1{loanId}~1payments/post/responses/409");
        assertThat(conflict.isMissingNode()).as("POST payments 409 response").isFalse();
        assertThat(conflict.at("/headers/Retry-After").isMissingNode())
                .as("POST payments 409 Retry-After header")
                .isFalse();
        assertThat(conflict.at("/content/application~1json/example").toString())
                .contains("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void rateLimitIsDocumentedWithRetryAfter() throws Exception {
        JsonNode doc = partnerDoc();
        for (String pointer : List.of(
                "/paths/~1api~1v1~1auth~1login/post/responses/429",
                "/paths/~1api~1v1~1auth~1token/post/responses/429",
                "/paths/~1api~1v1~1lsp~1loans~1{loanId}~1payments/post/responses/429",
                "/paths/~1api~1v1~1lsp~1loan-events/get/responses/429")) {
            JsonNode response = doc.at(pointer);
            assertThat(response.isMissingNode()).as("missing %s", pointer).isFalse();
            assertThat(response.at("/headers/Retry-After").isMissingNode())
                    .as("%s lacks Retry-After", pointer)
                    .isFalse();
        }
    }

    @Test
    void paginationContractIsDocumentedOnPagedLists() throws Exception {
        JsonNode doc = partnerDoc();
        for (String path : List.of(
                "/api/v1/lsp/loan-applications",
                "/api/v1/lsp/loans/{loanId}/payments")) {
            JsonNode operation = doc.path("paths").path(path).path("get");
            List<String> parameterNames = new ArrayList<>();
            operation.path("parameters").forEach(parameter -> parameterNames.add(parameter.path("name").asText()));
            assertThat(parameterNames).as("%s pagination params", path)
                    .contains("offset", "limit", "paginationDetails");
            JsonNode headers = operation.path("responses").path("200").path("headers");
            assertThat(headers.has("X-Limit")).as("%s X-Limit header", path).isTrue();
            assertThat(headers.has("X-Offset")).as("%s X-Offset header", path).isTrue();
            assertThat(headers.has("X-Total-Count")).as("%s X-Total-Count header", path).isTrue();
        }
    }

    @Test
    void routeSpecificErrorsAreMeaningfulNotBlanket() throws Exception {
        JsonNode doc = partnerDoc();
        // Cursor expiry exists only where a cursor contract exists.
        assertThat(doc.at("/paths/~1api~1v1~1lsp~1loan-events/get/responses/410").isMissingNode())
                .as("loan-events 410 CURSOR_EXPIRED")
                .isFalse();
        assertThat(doc.at("/paths/~1api~1v1~1lsp~1loan-events/get/responses/422").isMissingNode())
                .as("loan-events 422 INVALID_CURSOR")
                .isFalse();
        // The list endpoint documents its 422 status-filter contract.
        assertThat(doc.at("/paths/~1api~1v1~1lsp~1loan-applications/get/responses/422").isMissingNode())
                .as("loan-applications list 422 INVALID_STATUS")
                .isFalse();
        assertThat(doc.at("/paths/~1api~1v1~1lsp~1loan-applications~1{applicationId}~1repayment-schedule/put/responses/422")
                .isMissingNode())
                .as("repayment-schedule PUT 422")
                .isFalse();
        // A parameterless GET must not carry 404/409/410/422 — proves errors are
        // route-meaningful rather than blanket-attached.
        JsonNode invalidReasons = doc.at("/paths/~1api~1v1~1lsp~1loan-applications~1invalid-reasons/get/responses");
        for (String status : List.of("404", "409", "410", "422")) {
            assertThat(invalidReasons.has(status))
                    .as("invalid-reasons must not declare %s", status)
                    .isFalse();
        }
    }

    @Test
    void representativeExamplesArePresent() throws Exception {
        JsonNode doc = partnerDoc();
        // Success examples.
        assertThat(doc.at("/paths/~1api~1v1~1auth~1token/post/responses/200/content/*~1*/example").isMissingNode())
                .as("token success example")
                .isFalse();
        assertThat(doc.at("/paths/~1api~1v1~1lsp~1loan-events/get/responses/200/content/*~1*/example").isMissingNode())
                .as("loan-events success example")
                .isFalse();
        // Conflict and validation error examples carry the ApiError shape.
        JsonNode conflictExample = doc.at(
                "/paths/~1api~1v1~1lsp~1loans~1{loanId}~1payments/post/responses/409/content/application~1json/example");
        assertThat(conflictExample.path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        JsonNode validationExample = doc.at(
                "/paths/~1api~1v1~1lsp~1loan-applications/post/responses/400/content/application~1json/example");
        assertThat(validationExample.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(validationExample.path("violations").isArray()).isTrue();
    }

    @Test
    void everyPartnerOperationHasASummary() throws Exception {
        JsonNode doc = partnerDoc();
        forEachOperation(doc, (path, method, operation) -> assertThat(operation.path("summary").asText())
                .as("%s %s summary", method, path)
                .isNotBlank());
    }

    @Test
    void internalAdminOperationsAreAbsentFromPartnerDocument() throws Exception {
        JsonNode doc = partnerDoc();
        List<String> internalPaths = new ArrayList<>();
        doc.path("paths").fieldNames().forEachRemaining(path -> {
            if (path.startsWith("/api/v1/internal")) {
                internalPaths.add(path);
            }
        });
        assertThat(internalPaths).isEmpty();
    }

    private JsonNode partnerDoc() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs/partner").with(lspClient()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private static boolean isPublicAuthRoute(String path) {
        return path.startsWith("/api/v1/auth/")
                && List.of("/api/v1/auth/login", "/api/v1/auth/token",
                        "/api/v1/auth/refresh", "/api/v1/auth/logout").contains(path);
    }

    private static JsonNode idempotencyKeyParam(JsonNode doc, String path, String method) {
        JsonNode parameters = doc.path("paths").path(path).path(method).path("parameters");
        for (JsonNode parameter : parameters) {
            if ("Idempotency-Key".equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        throw new AssertionError("No Idempotency-Key parameter on " + method + " " + path);
    }

    private static void forEachOperation(JsonNode doc, OperationAssertion assertion) {
        for (Map.Entry<String, JsonNode> pathItem : doc.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : pathItem.getValue().properties()) {
                if (List.of("get", "put", "post", "delete", "patch", "options", "head", "trace")
                        .contains(operation.getKey())) {
                    assertion.assertOperation(pathItem.getKey(), operation.getKey(), operation.getValue());
                }
            }
        }
    }

    @FunctionalInterface
    private interface OperationAssertion {
        void assertOperation(String path, String method, JsonNode operation);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspClient() {
        return jwt().jwt(token -> token.subject("acme-api-client").claim("roles", List.of("LSP_API_CLIENT")))
                .authorities(() -> "ROLE_LSP_API_CLIENT");
    }
}
