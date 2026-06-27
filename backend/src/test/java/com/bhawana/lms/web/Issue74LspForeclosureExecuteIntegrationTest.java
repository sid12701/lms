package com.bhawana.lms.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class Issue74LspForeclosureExecuteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;

    @Autowired
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private WebhookEventOutboxRepository webhookEventOutboxRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void lspCanExecuteActiveForeclosureQuoteAndLoanCloses() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-EXEC-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-12345",
                                "note", "Borrower settled in full"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.id").value(quoteId));

        mockMvc.perform(get("/api/v1/lsp/loans/{loanId}", fixture.loanAccountId())
                        .header("Authorization", "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FORECLOSED"))
                .andExpect(jsonPath("$.loanAccount.status").value("FORECLOSED"));

        assertTrue(loanApplicationAuditEventRepository.findTop25ByLoanApplication_IdOrderByCreatedAtDesc(
                        UUID.fromString(fixture.applicationId()))
                .stream()
                .anyMatch(event -> event.getAction() == LoanApplicationAuditAction.FORECLOSURE_EXECUTED
                        && fixture.clientId().equals(event.getActorUsername())));

        assertTrue(webhookEventOutboxRepository.findAll().stream()
                .anyMatch(event -> event.getEventType() == WebhookEventType.LOAN_FORECLOSURE_COMPLETED));
    }

    @Test
    void lspUiWriteRoleCanExecuteForeclosureQuote() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-UI-WRITE-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .with(lspUiWriteUser(fixture.lspId(), "FC LSP"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-UI-001"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    void lspUiReadRoleCannotExecuteForeclosureQuote() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-UI-READ-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .with(lspUiReadUser(fixture.lspId(), "FC LSP"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-READ-001"
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantLspCannotExecuteAnotherLspsForeclosureQuote() throws Exception {
        DisbursedLoanFixture alpha = seedDisbursedLoan("FC-TENANT-A");
        DisbursedLoanFixture beta = seedDisbursedLoan("FC-TENANT-B");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(alpha.accessToken(), alpha.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        alpha.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + beta.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-CROSS"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("Unknown loan id")));

        assertEquals(0, opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION)
                .count());
    }

    @Test
    void idempotencyKeySameRequestReturnsCachedResponseAndNoDoubleSettlement() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-IDEM-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(Map.of(
                "settlementDate", effectiveDate.toString(),
                "reference", "BNK-IDEM-001"
        ));

        MvcResult first = mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(
                first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString()
        );
        assertEquals(
                1,
                loanPaymentTransactionRepository.findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(
                                UUID.fromString(fixture.loanAccountId()))
                        .stream()
                        .filter(payment -> "BNK-IDEM-001".equals(payment.getReference()))
                        .count()
        );
    }

    @Test
    void idempotencyKeyMissingReturnsBadRequest() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-IDEM-MISSING");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-NO-KEY"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settlementDateMismatchReturns422AndFiresLspBoundViolationAlert() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-DATE-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.minusDays(1).toString(),
                                "reference", "BNK-DATE"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LSP_BOUND_VIOLATION"))
                .andExpect(jsonPath("$.violations[0].message").value("SETTLEMENT_DATE_MISMATCH"));

        assertTrue(opsAlertRepository.findAll().stream()
                .anyMatch(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION
                        && alert.getContextJson().contains("SETTLEMENT_DATE_MISMATCH")));
    }

    @Test
    void inactiveQuoteReturns422AndFiresLspBoundViolationAlert() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-INACTIVE-001");
        LocalDate firstDate = LocalDate.now();
        LocalDate secondDate = firstDate.plusDays(1);
        String firstQuoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), firstDate);
        requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), secondDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        firstQuoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", firstDate.toString(),
                                "reference", "BNK-STALE"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LSP_BOUND_VIOLATION"))
                .andExpect(jsonPath("$.violations[0].message").value("QUOTE_NOT_ACTIVE"));

        assertTrue(opsAlertRepository.findAll().stream()
                .anyMatch(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION
                        && alert.getContextJson().contains("QUOTE_NOT_ACTIVE")));
    }

    @Test
    void adminExecuteSameFailureDoesNotFireLspBoundViolationAlert() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-ADMIN-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.applicationId(),
                        quoteId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.minusDays(1).toString(),
                                "reference", "FC-ADMIN"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SETTLEMENT_DATE_MISMATCH"));

        assertEquals(0, opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION)
                .count());
    }

    private String requestForeclosureQuote(String accessToken, String loanAccountId, LocalDate effectiveDate)
            throws Exception {
        MvcResult quoteResult = mockMvc.perform(post("/api/v1/lsp/loans/{loanId}/foreclosure-quote", loanAccountId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveDate", effectiveDate.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return objectMapper.readTree(quoteResult.getResponse().getContentAsString()).get("id").asText();
    }

    private DisbursedLoanFixture seedDisbursedLoan(String externalLoanId) throws Exception {
        String lspId = createLspViaAdmin("FC-LSP");
        enableWebhook(lspId, List.of("LOAN_FORECLOSURE_COMPLETED"));
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String clientId = apiClient.get("clientId").asText();
        String accessToken = issueToken(apiClient);

        JsonNode application = createApplicationViaLsp(accessToken, lspId, productId, externalLoanId);
        String applicationId = application.get("id").asText();
        markKycComplete(applicationId);
        transitionToAwaitingApproval(applicationId);
        transitionToApproved(applicationId);
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);
        assertEquals(
                LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow().getStatus()
        );

        MvcResult detail = mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        String loanAccountId = objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("loanAccount")
                .get("id")
                .asText();

        return new DisbursedLoanFixture(lspId, applicationId, loanAccountId, accessToken, clientId);
    }

    private void requestDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private void resolveDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome",
                        applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());
    }

    private void enableWebhook(String lspId, List<String> eventTypes) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://partner.example.com/webhooks/lms",
                                "signingSecret", "whsec_foreclosure",
                                "eventTypes", eventTypes
                        ))))
                .andExpect(status().isOk());
    }

    private JsonNode createApiClient(String lspId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Foreclosure LSP client",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String issueToken(JsonNode apiClient) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                apiClient.get("clientId").asText(),
                                apiClient.get("clientSecret").asText()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode createApplicationViaLsp(
            String accessToken,
            String lspId,
            String productId,
            String externalLoanId
    ) throws Exception {
        String pan = uniquePan();
        MvcResult result = mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                defaultExternalApplicationPayload(lspId, productId, externalLoanId, pan))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createLspViaAdmin(String codeSuffix) throws Exception {
        String code = "LSP-" + codeSuffix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Foreclosure LSP " + codeSuffix,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createProductViaAdmin() throws Exception {
        String code = "PROD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Foreclosure product " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private void transitionToAwaitingApproval(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Ready for approval"
                        ))))
                .andExpect(status().isOk());
    }

    private void transitionToApproved(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Approved for foreclosure test"
                        ))))
                .andExpect(status().isOk());
    }

    private void markKycComplete(String applicationId) {
        loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
                .forEach(item -> {
                    if (!item.isRequired()) {
                        return;
                    }
                    String documentKey = item.getDocumentType().name().toLowerCase();
                    item.update(
                            LoanApplicationDocumentChecklistStatus.SUBMITTED,
                            "Uploaded for foreclosure test",
                            "ops.user",
                            documentKey + ".pdf",
                            "storage://" + applicationId + "/" + documentKey + ".pdf",
                            null,
                            "application/pdf",
                            1024L,
                            "checksum-" + documentKey,
                            "storage-key/" + applicationId + "/" + documentKey,
                            true
                    );
                    loanApplicationDocumentChecklistRepository.save(item);
                });
    }

    private static Map<String, Object> defaultExternalApplicationPayload(
            String lspId,
            String productId,
            String externalLoanId,
            String pan
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("lspLoanId", externalLoanId);
        payload.put("fullName", "Foreclosure Borrower");
        payload.put("emailAddress", externalLoanId.toLowerCase() + "@example.com");
        payload.put("mobileNumber", mobileForPan(pan));
        payload.put("dob", "1990-01-01");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Ramesh Sharma");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", pan);
        payload.put("loanAmount", new BigDecimal("45000.00"));
        payload.put("interestRate", new BigDecimal("18.50"));
        payload.put("loanTenure", 12);
        payload.put("addressLine1", "Palm Residency");
        payload.put("addressLine2", "Andheri East");
        payload.put("addressCity", "Mumbai");
        payload.put("addressState", "Maharashtra");
        payload.put("addressZipcode", "400001");
        payload.put("employmentStatus", "SALARIED");
        payload.put("organizationName", "Apex Corp");
        payload.put("empId", "EMP-001");
        payload.put("employmentCity", "Mumbai");
        payload.put("employmentState", "Maharashtra");
        payload.put("employmentZip", "400001");
        payload.put("monthlyIncome", new BigDecimal("78000.00"));
        payload.put("annualIncome", new BigDecimal("936000.00"));
        payload.put("bankAccountNumber", "123456789012");
        payload.put("bankName", "Demo Bank");
        payload.put("ifscCode", "HDFC0001234");
        payload.put("accountHolderName", "Foreclosure Borrower");
        payload.put("referencePersonName", "Neha Verma");
        payload.put("referencePersonNumber", "9888877777");
        return payload;
    }

    private static String uniquePan() {
        int suffix = Math.abs(UUID.randomUUID().hashCode()) % 10_000;
        return String.format("ABCDE%04dF", suffix);
    }

    private static String mobileForPan(String pan) {
        int hash = Math.abs(pan.hashCode());
        return "9" + String.format("%09d", hash % 1_000_000_000);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(token -> token.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspUiReadUser(
            String lspId,
            String lspName
    ) {
        return jwt().jwt(token -> token
                        .subject("tenant.viewer")
                        .claim("roles", List.of("LSP_UI_READ"))
                        .claim("lspId", lspId)
                        .claim("lspName", lspName))
                .authorities(() -> "ROLE_LSP_UI_READ");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspUiWriteUser(
            String lspId,
            String lspName
    ) {
        return jwt().jwt(token -> token
                        .subject("tenant.writer")
                        .claim("roles", List.of("LSP_UI_WRITE"))
                        .claim("lspId", lspId)
                        .claim("lspName", lspName))
                .authorities(() -> "ROLE_LSP_UI_WRITE");
    }

    private record DisbursedLoanFixture(
            String lspId,
            String applicationId,
            String loanAccountId,
            String accessToken,
            String clientId
    ) {
    }
}
