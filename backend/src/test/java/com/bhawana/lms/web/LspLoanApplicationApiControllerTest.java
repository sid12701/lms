package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAssignmentEventRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentAccessAuditRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanProductAuditEventRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LspLoanApplicationApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Autowired
    private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;

    @Autowired
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Autowired
    private LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;

    @Autowired
    private LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;

    @Autowired
    private LoanApplicationDocumentAccessAuditRepository loanApplicationDocumentAccessAuditRepository;

    @Autowired
    private LoanApplicationAssignmentEventRepository loanApplicationAssignmentEventRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Autowired
    private LoanProductAuditEventRepository loanProductAuditEventRepository;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @BeforeEach
    void setUp() {
        loanForeclosureQuoteRepository.deleteAllInBatch();
        loanPaymentTransactionRepository.deleteAllInBatch();
        loanDisbursementRequestLogRepository.deleteAllInBatch();
        loanRepaymentScheduleInstallmentRepository.deleteAllInBatch();
        loanAccountRepository.deleteAllInBatch();
        loanApplicationAuditEventRepository.deleteAllInBatch();
        loanApplicationDocumentAccessAuditRepository.deleteAllInBatch();
        loanApplicationAssignmentEventRepository.deleteAllInBatch();
        loanApplicationDocumentChecklistRepository.deleteAllInBatch();
        loanApplicationStatusTransitionRepository.deleteAllInBatch();
        loanApplicationIntakeAuditRepository.deleteAllInBatch();
        loanApplicationRepository.deleteAllInBatch();
        borrowerRepository.deleteAllInBatch();
        apiClientRepository.deleteAllInBatch();
        loanProductAuditEventRepository.deleteAllInBatch();
        loanProductLspMappingRepository.deleteAllInBatch();
        loanProductRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }

    @Test
    void apiClientCanMintTokenAndWorkOnlyWithinOwnLspScope() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        ProductFixture northProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());
        mapProductToLsp(northProduct.id(), north.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", apexProduct.id());
        payload.put("externalLoanId", "APEX-EXT-001");
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", "ABCDE1234F");
        payload.put("borrowerFullName", "Anika Sharma");
        payload.put("borrowerMobile", "9999999999");
        payload.put("borrowerEmail", "anika@example.com");
        payload.put("borrowerDateOfBirth", "1992-03-10");
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("78000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);

        mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lspId").value(apex.id()))
                .andExpect(jsonPath("$.externalLoanId").value("APEX-EXT-001"))
                .andExpect(jsonPath("$.status").value("INITIALIZED"));

        JsonNode northApplication = createInternalApplication(
                north.id(),
                northProduct.id(),
                "NORTH-EXT-001",
                "ZXCVB1234N"
        );

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("APEX-EXT-001"));

        mockMvc.perform(get("/api/v1/lsp/loan-applications/external/{externalLoanId}", "APEX-EXT-001")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalLoanId").value("APEX-EXT-001"))
                .andExpect(jsonPath("$.lastActivity.actorUsername").value(apiClient.get("clientId").asText()))
                .andExpect(jsonPath("$.loanAccount").doesNotExist());

        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", northApplication.get("id").asText())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void apiClientCannotCreateLoanForUnmappedProduct() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture northProduct = createProduct("ACTIVE");
        mapProductToLsp(northProduct.id(), north.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", northProduct.id(),
                                "externalLoanId", "APEX-EXT-404",
                                "sourceChannel", "API",
                                "borrowerPan", "ABCDE1234F",
                                "borrowerFullName", "Anika Sharma",
                                "borrowerMobile", "9999999999",
                                "borrowerEmail", "anika@example.com",
                                "requestedAmount", new BigDecimal("45000.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Requested product is not mapped to the selected LSP."));
    }

    @Test
    void lspUiUserCanReadScopedLoansButCannotCreateThem() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        ProductFixture northProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());
        mapProductToLsp(northProduct.id(), north.id());

        JsonNode apexApplication = createInternalApplication(
                apex.id(),
                apexProduct.id(),
                "APEX-UI-001",
                "ABCDE1234F"
        );
        createInternalApplication(
                north.id(),
                northProduct.id(),
                "NORTH-UI-001",
                "ZXCVB1234N"
        );

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .with(lspUiUser(apex.id(), "Apex Tenant")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("APEX-UI-001"));

        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", apexApplication.get("id").asText())
                        .with(lspUiUser(apex.id(), "Apex Tenant")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalLoanId").value("APEX-UI-001"));

        mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .with(lspUiUser(apex.id(), "Apex Tenant"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", apexProduct.id(),
                                "externalLoanId", "APEX-UI-002",
                                "sourceChannel", "API",
                                "borrowerPan", "QWERT1234Y",
                                "borrowerFullName", "Blocked Viewer",
                                "borrowerMobile", "9999999999",
                                "requestedAmount", new BigDecimal("45000.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiClientCanSubmitDocumentsAndReadLoanServicingEndpointsWithinScope() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-SERV-001");
        String applicationId = createdApplication.get("id").asText();

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "documentType", "PAN_CARD",
                                "note", "PAN uploaded from partner LOS",
                                "fileName", "pan-card.pdf",
                                "fileReference", "minio://tenant-apex/pan-card.pdf",
                                "sourceReference", "los-doc-001",
                                "contentType", "application/pdf"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("PAN_CARD"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.fileName").value("pan-card.pdf"));

        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "AWAITING_APPROVAL");
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", systemAdmin());
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);
        recordPayment(applicationId);

        JsonNode externalDetail = getApplicationDetail(accessToken, applicationId);
        String loanId = externalDetail.get("loanAccount").get("id").asText();

        mockMvc.perform(get("/api/v1/lsp/loans/{loanId}", loanId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(applicationId))
                .andExpect(jsonPath("$.loanAccount.id").value(loanId))
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSED"));

        mockMvc.perform(get("/api/v1/lsp/loans/{loanId}/repayment-schedule", loanId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].loanAccountId").value(loanId));

        mockMvc.perform(get("/api/v1/lsp/loans/{loanId}/payments", loanId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].loanAccountId").value(loanId))
                .andExpect(jsonPath("$[0].reference").value("PAY-LSP-001"));

        mockMvc.perform(post("/api/v1/lsp/loans/{loanId}/foreclosure-quote", loanId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveDate", LocalDate.now().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccountId").value(loanId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void apiClientCannotAccessAnotherLspLoanEndpoints() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        ProductFixture northProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());
        mapProductToLsp(northProduct.id(), north.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode northApplication = createInternalApplication(
                north.id(),
                northProduct.id(),
                "NORTH-LOAN-001",
                "ZXCVB1234N"
        );
        markAllRequiredKycDocumentsVerified(northApplication.get("id").asText());
        transitionApplication(northApplication.get("id").asText(), "AWAITING_APPROVAL");
        transitionApplication(northApplication.get("id").asText(), "APPROVED_PENDING_DISBURSAL", systemAdmin());

        JsonNode northDetail = getInternalApplicationDetail(northApplication.get("id").asText());
        String northLoanId = northDetail.get("loanAccount").get("id").asText();

        mockMvc.perform(get("/api/v1/lsp/loans/{loanId}", northLoanId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/documents", northApplication.get("id").asText())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "documentType", "PAN_CARD",
                                "fileName", "blocked.pdf"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    private JsonNode createInternalApplication(
            String lspId,
            String productId,
            String externalLoanId,
            String borrowerPan
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lspId,
                                productId,
                                externalLoanId,
                                "API",
                                borrowerPan,
                                "Internal Borrower",
                                "9898989898",
                                "internal@example.com",
                                LocalDate.of(1991, 4, 12),
                                "Delhi",
                                "Delhi",
                                "SALARIED",
                                new BigDecimal("72000.00"),
                                new BigDecimal("33000.00"),
                                12
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createExternalApplication(String accessToken, String productId, String externalLoanId) throws Exception {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", "ABCDE1234F");
        payload.put("borrowerFullName", "Anika Sharma");
        payload.put("borrowerMobile", "9999999999");
        payload.put("borrowerEmail", "anika@example.com");
        payload.put("borrowerDateOfBirth", "1992-03-10");
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("78000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getApplicationDetail(String accessToken, String applicationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getInternalApplicationDetail(String applicationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void transitionApplication(String applicationId, String targetStatus) throws Exception {
        transitionApplication(applicationId, targetStatus, opsUser());
    }

    private void transitionApplication(
            String applicationId,
            String targetStatus,
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor actor
    ) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", "Transition to " + targetStatus
                        ))))
                .andExpect(status().isOk());
    }

    private void markAllRequiredKycDocumentsVerified(String applicationId) throws Exception {
        for (String documentType : List.of("PAN_CARD", "ADDRESS_PROOF", "INCOME_PROOF", "BANK_STATEMENT")) {
            mockMvc.perform(put("/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}",
                            applicationId,
                            documentType)
                            .with(opsUser())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "status", "VERIFIED",
                                    "note", "Verified",
                                    "reviewReason", "Checked"
                            ))))
                    .andExpect(status().isOk());
        }
    }

    private void requestDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private void resolveDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());
    }

    private void recordPayment(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", new BigDecimal("4136.32"),
                                "paymentDate", LocalDate.now().minusDays(1).toString(),
                                "reference", "PAY-LSP-001",
                                "channel", "UPI",
                                "status", "RECEIVED"
                        ))))
                .andExpect(status().isOk());
    }

    private JsonNode createApiClient(String lspId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String issueClientCredentialsToken(String clientId, String clientSecret) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "grantType", "client_credentials",
                                "clientId", clientId,
                                "clientSecret", clientSecret
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private ProductFixture createProduct(String status) throws Exception {
        String code = "PRODUCT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Product " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        return new ProductFixture(createdJson.get("id").asText(), createdJson.get("code").asText());
    }

    private LspFixture createLsp(String status) throws Exception {
        String code = "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode lspJson = objectMapper.readTree(lspResult.getResponse().getContentAsString());
        return new LspFixture(lspJson.get("id").asText(), lspJson.get("code").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> loanApplicationPayload(
            String lspId,
            String productId,
            String externalLoanId,
            String sourceChannel,
            String borrowerPan,
            String borrowerFullName,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            BigDecimal requestedAmount,
            int tenureMonths
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", sourceChannel);
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", borrowerFullName);
        payload.put("borrowerMobile", borrowerMobile);
        payload.put("borrowerEmail", borrowerEmail);
        payload.put("borrowerDateOfBirth", borrowerDateOfBirth);
        payload.put("borrowerCity", borrowerCity);
        payload.put("borrowerState", borrowerState);
        payload.put("borrowerEmploymentType", borrowerEmploymentType);
        payload.put("borrowerMonthlyIncome", borrowerMonthlyIncome);
        payload.put("requestedAmount", requestedAmount);
        payload.put("tenureMonths", tenureMonths);
        return payload;
    }

    private record ProductFixture(String id, String code) {
    }

    private record LspFixture(String id, String code) {
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(jwt -> jwt.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspUiUser(
            String lspId,
            String lspName
    ) {
        return jwt().jwt(jwt -> jwt
                        .subject("tenant.viewer")
                        .claim("roles", List.of("LSP_UI_READ"))
                        .claim("lspId", lspId)
                        .claim("lspName", lspName))
                .authorities(() -> "ROLE_LSP_UI_READ");
    }
}
