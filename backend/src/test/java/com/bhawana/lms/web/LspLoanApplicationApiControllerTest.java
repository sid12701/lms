package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.ApiClientAuditEventRepository;
import com.bhawana.lms.repo.ApiClientIpAllowlistRepository;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAssignmentEventRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentAccessAuditRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationIntakeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationPiiRevealAuditRepository;
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
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

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
    private LoanApplicationPiiRevealAuditRepository loanApplicationPiiRevealAuditRepository;

    @Autowired
    private ApiClientAuditEventRepository apiClientAuditEventRepository;

    @Autowired
    private ApiClientIpAllowlistRepository apiClientIpAllowlistRepository;

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

    @Autowired
    private LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

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
        loanApplicationPiiRevealAuditRepository.deleteAllInBatch();
        lspApiIdempotencyRecordRepository.deleteAllInBatch();
        opsAlertRepository.deleteAllInBatch();
        loanApplicationIntakeAuditRepository.deleteAllInBatch();
        loanApplicationRepository.deleteAllInBatch();
        borrowerRepository.deleteAllInBatch();
        apiClientIpAllowlistRepository.deleteAllInBatch();
        apiClientAuditEventRepository.deleteAllInBatch();
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
        payload.put("lspId", apex.id());
        payload.put("productId", apexProduct.id());
        payload.put("lspLoanId", "APEX-EXT-001");
        payload.put("fullName", "Anika Sharma");
        payload.put("emailAddress", "anika@example.com");
        payload.put("mobileNumber", "9999999999");
        payload.put("dob", "1992-03-10");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Ramesh Sharma");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", "ABCDE1234F");
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
        payload.put("accountHolderName", "Anika Sharma");
        payload.put("referencePersonName", "Neha Verma");
        payload.put("referencePersonNumber", "9888877777");

        mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lspId").value(apex.id()))
                .andExpect(jsonPath("$.lspLoanId").value("APEX-EXT-001"))
                .andExpect(jsonPath("$.aadharNumber").value("XXXXXXXX1234"))
                .andExpect(jsonPath("$.panNumber").value("ABCDE1234F"))
                .andExpect(jsonPath("$.empId").value("EMP-001"))
                .andExpect(jsonPath("$.bankAccountNumber").value("123456789012"))
                .andExpect(jsonPath("$.ifscCode").value("HDFC0001234"))
                .andExpect(jsonPath("$.accountHolderName").value("Anika Sharma"))
                .andExpect(jsonPath("$.referencePersonName").value("Neha Verma"))
                .andExpect(jsonPath("$.referencePersonNumber").value("9888877777"))
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
                .andExpect(jsonPath("$[0].lspLoanId").value("APEX-EXT-001"))
                .andExpect(jsonPath("$[0].aadharNumber").value("XXXXXXXX1234"))
                .andExpect(jsonPath("$[0].panNumber").value("ABCDE1234F"))
                .andExpect(jsonPath("$[0].bankAccountNumber").value("123456789012"));

        mockMvc.perform(get("/api/v1/lsp/loan-applications/external/{externalLoanId}", "APEX-EXT-001")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lspLoanId").value("APEX-EXT-001"))
                .andExpect(jsonPath("$.aadharNumber").value("XXXXXXXX1234"))
                .andExpect(jsonPath("$.panNumber").value("ABCDE1234F"))
                .andExpect(jsonPath("$.empId").value("EMP-001"))
                .andExpect(jsonPath("$.bankAccountNumber").value("123456789012"))
                .andExpect(jsonPath("$.ifscCode").value("HDFC0001234"))
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

        LinkedHashMap<String, Object> unmappedPayload = new LinkedHashMap<>();
        unmappedPayload.put("lspId", apex.id());
        unmappedPayload.put("productId", northProduct.id());
        unmappedPayload.put("lspLoanId", "APEX-EXT-404");
        unmappedPayload.put("fullName", "Anika Sharma");
        unmappedPayload.put("mobileNumber", "9999999999");
        unmappedPayload.put("emailAddress", "anika@example.com");
        unmappedPayload.put("aadharNumber", "123412341234");
        unmappedPayload.put("panNumber", "ABCDE1234F");
        unmappedPayload.put("loanAmount", new BigDecimal("45000.00"));
        unmappedPayload.put("loanTenure", 12);
        unmappedPayload.put("monthlyIncome", new BigDecimal("78000.00"));

        mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unmappedPayload)))
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
                .andExpect(jsonPath("$[0].lspLoanId").value("APEX-UI-001"));

        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", apexApplication.get("id").asText())
                        .with(lspUiUser(apex.id(), "Apex Tenant")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lspLoanId").value("APEX-UI-001"))
                .andExpect(jsonPath("$.aadharNumber").isEmpty())
                .andExpect(jsonPath("$.panNumber").value("ABCDE1234F"));

        mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .with(lspUiUser(apex.id(), "Apex Tenant"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", apex.id(),
                                "productId", apexProduct.id(),
                                "lspLoanId", "APEX-UI-002",
                                "fullName", "Blocked Viewer",
                                "mobileNumber", "9999999999",
                                "aadharNumber", "123412341234",
                                "panNumber", "QWERT1234Y",
                                "loanAmount", new BigDecimal("45000.00"),
                                "loanTenure", 12,
                                "monthlyIncome", new BigDecimal("55000.00")
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void lspApiMasksAadhaarButReturnsOtherPiiRawAndRevealEndpointIsRemoved() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-PII-001");
        String applicationId = createdApplication.get("id").asText();

        // Aadhaar stays masked on every LSP read site; other borrower PII (PAN,
        // bank account, IFSC, employment / reference fields) is returned raw to
        // the partner. There is no reveal path: the legacy borrower-pii endpoint
        // is removed and writes no audit.
        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aadharNumber").value("XXXXXXXX1234"))
                .andExpect(jsonPath("$.panNumber").value("ABCDE1234F"))
                .andExpect(jsonPath("$.bankAccountNumber").value("123456789012"))
                .andExpect(jsonPath("$.ifscCode").value("HDFC0001234"))
                .andExpect(jsonPath("$.accountHolderName").value("Anika Sharma"))
                .andExpect(jsonPath("$.referencePersonName").value("Neha Verma"))
                .andExpect(jsonPath("$.referencePersonNumber").value("9888877777"));

        // The historical borrower-pii reveal endpoint is removed. Any caller hits 404,
        // regardless of role. No audit row is written.
        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}/borrower-pii", applicationId)
                        .with(lspUiWriteUser(apex.id(), "Apex Tenant")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}/borrower-pii", applicationId)
                        .with(lspUiUser(apex.id(), "Apex Tenant")))
                .andExpect(status().isNotFound());

        assertEquals(0L, loanApplicationPiiRevealAuditRepository.count());
    }

    @Test
    void onboardingReusesGlobalBorrowerByPanUpdatesLatestProfileAndExpandsLspVisibility() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        ProductFixture northProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());
        mapProductToLsp(northProduct.id(), north.id());

        JsonNode apexClient = createApiClient(apex.id(), "Apex Integration");
        JsonNode northClient = createApiClient(north.id(), "North Integration");
        String apexAccessToken = issueClientCredentialsToken(
                apexClient.get("clientId").asText(),
                apexClient.get("clientSecret").asText()
        );
        String northAccessToken = issueClientCredentialsToken(
                northClient.get("clientId").asText(),
                northClient.get("clientSecret").asText()
        );

        JsonNode firstApplication = createExternalApplication(apexAccessToken, apexProduct.id(), "APEX-DEDUPE-001");
        LinkedHashMap<String, Object> updatedPayload = defaultExternalApplicationPayload(
                extractLspIdFromToken(northAccessToken),
                northProduct.id(),
                "NORTH-DEDUPE-001"
        );
        updatedPayload.put("fullName", "Anika Rao");
        updatedPayload.put("emailAddress", "anika.rao@example.com");
        updatedPayload.put("addressCity", "Pune");
        updatedPayload.put("organizationName", "North Corp");
        updatedPayload.put("empId", "EMP-999");
        updatedPayload.put("referencePersonName", "Priya Kapoor");
        updatedPayload.put("referencePersonNumber", "9777766666");

        JsonNode secondApplication = createExternalApplication(northAccessToken, updatedPayload);

        assertEquals(firstApplication.get("borrowerId").asText(), secondApplication.get("borrowerId").asText());
        assertEquals(1L, borrowerRepository.count());

        com.bhawana.lms.domain.Borrower borrower = borrowerRepository.findById(
                        UUID.fromString(firstApplication.get("borrowerId").asText()))
                .orElseThrow();
        assertEquals("Anika Rao", borrower.getFullName());
        assertEquals("anika.rao@example.com", borrower.getEmail());
        assertEquals("Pune", borrower.getCity());
        assertEquals("North Corp", borrower.getOrganizationName());
        assertEquals("EMP-999", borrower.getEmployeeId());
        assertEquals("Priya Kapoor", borrower.getReferencePersonName());
        assertEquals("9777766666", borrower.getReferencePersonNumber());
        assertEquals(
                Set.of(UUID.fromString(apex.id()), UUID.fromString(north.id())),
                borrower.getVisibleLspIds()
        );
    }

    @Test
    void onboardingConflictOnExistingMobileWithDifferentPanCreatesOpsAlert() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        ProductFixture northProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());
        mapProductToLsp(northProduct.id(), north.id());

        JsonNode apexClient = createApiClient(apex.id(), "Apex Integration");
        JsonNode northClient = createApiClient(north.id(), "North Integration");
        String apexAccessToken = issueClientCredentialsToken(
                apexClient.get("clientId").asText(),
                apexClient.get("clientSecret").asText()
        );
        String northAccessToken = issueClientCredentialsToken(
                northClient.get("clientId").asText(),
                northClient.get("clientSecret").asText()
        );

        createExternalApplication(apexAccessToken, apexProduct.id(), "APEX-CONFLICT-001");

        LinkedHashMap<String, Object> conflictingPayload = defaultExternalApplicationPayload(
                extractLspIdFromToken(northAccessToken),
                northProduct.id(),
                "NORTH-CONFLICT-001"
        );
        conflictingPayload.put("fullName", "Anika Sharma");
        conflictingPayload.put("panNumber", "ZXCVB1234N");
        conflictingPayload.put("aadharNumber", "987698769876");

        mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + northAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictingPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BORROWER_IDENTITY_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Borrower identity conflict detected. Internal ops has been alerted."));

        assertEquals(1L, opsAlertRepository.count());

        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("BORROWER_IDENTITY_CONFLICT"))
                .andExpect(jsonPath("$[0].severity").value("HIGH"))
                .andExpect(jsonPath("$[0].status").value("NEW"));
    }

    @Test
    void apiClientCanReadInvalidLoanReasonsInvalidateApplicationAndReplayIdempotentResponse() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-INVALID-001");
        String applicationId = createdApplication.get("id").asText();
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/v1/lsp/loan-applications/invalid-reasons")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].code").value("REASON_A"))
                .andExpect(jsonPath("$[3].code").value("OTHERS"))
                .andExpect(jsonPath("$[3].requiresText").value(true));

        MvcResult firstInvalidation = mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "REASON_A"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.invalidReasonCode").value("REASON_A"))
                .andExpect(jsonPath("$.invalidReasonText").doesNotExist())
                .andExpect(jsonPath("$.invalidatedByUsername").value(apiClient.get("clientId").asText()))
                .andExpect(jsonPath("$.invalidatedAt").exists())
                .andExpect(jsonPath("$.loanAccount").doesNotExist())
                .andReturn();

        MvcResult replayedInvalidation = mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "REASON_A"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.invalidReasonCode").value("REASON_A"))
                .andReturn();

        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.invalidReasonCode").value("REASON_A"));

        assertEquals(
                firstInvalidation.getResponse().getContentAsString(),
                replayedInvalidation.getResponse().getContentAsString()
        );
        assertEquals(1L, lspApiIdempotencyRecordRepository.count());
        assertEquals(
                1L,
                loanApplicationStatusTransitionRepository
                        .findTop20ByLoanApplication_IdOrderByCreatedAtDesc(UUID.fromString(applicationId))
                        .stream()
                        .filter(transition -> transition.getToStatus().name().equals("INVALID"))
                        .count()
        );
        assertTrue(loanApplicationStatusTransitionRepository
                .findTop20ByLoanApplication_IdOrderByCreatedAtDesc(UUID.fromString(applicationId))
                .stream()
                .anyMatch(transition -> transition.getToStatus().name().equals("INVALID")
                        && transition.getNote().contains("Reason A")));
    }

    @Test
    void apiClientCanPaginateScopedLoanListingsAndReadPaginationHeaders() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Pagination");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        createExternalApplication(accessToken, apexProduct.id(), "APEX-PAGE-001");
        createExternalApplication(accessToken, apexProduct.id(), "APEX-PAGE-002");
        createExternalApplication(accessToken, apexProduct.id(), "APEX-PAGE-003");

        mockMvc.perform(get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .queryParam("offset", "1")
                        .queryParam("limit", "1")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lspLoanId").value("APEX-PAGE-002"))
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(header().string("X-Limit", "1"))
                .andExpect(header().string("X-Offset", "1"));
    }

    @Test
    void apiClientCanInvalidateApprovedApplicationAndLoanAccountIsAlsoMarkedInvalid() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-INVALID-LOAN-001");
        String applicationId = createdApplication.get("id").asText();
        String idempotencyKey = UUID.randomUUID().toString();
        uploadAllRequiredDocuments(accessToken, applicationId);

        JsonNode approvedLoan = getApplicationDetail(accessToken, applicationId);
        String loanId = approvedLoan.get("loanAccount").get("id").asText();

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "OTHERS",
                                "reasonText", "Duplicate onboarding from partner LOS"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.invalidReasonCode").value("OTHERS"))
                .andExpect(jsonPath("$.invalidReasonText").value("Duplicate onboarding from partner LOS"))
                .andExpect(jsonPath("$.loanAccount.id").value(loanId))
                .andExpect(jsonPath("$.loanAccount.status").value("INVALID"));
    }

    @Test
    void invalidLoanIdempotencyAndReasonValidationRulesAreEnforced() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-INVALID-002");
        String applicationId = createdApplication.get("id").asText();
        String validIdempotencyKey = UUID.randomUUID().toString();
        String reusedIdempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "REASON_A"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required."));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "REASON_A"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key must be a UUID v4 value."));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", validIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "OTHERS"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Other invalid loan reason text is required when reason is OTHERS."
                ));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "REASON_B",
                                "reasonText", "Should not be accepted"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Other invalid loan reason text is only allowed when reason is OTHERS."
                ));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", reusedIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "REASON_A"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVALID"));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", reusedIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "REASON_C"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "Idempotency-Key has already been used for a different request."
                ));
    }

    @Test
    void postDisbursalInvalidationIsRejected() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-INVALID-003");
        String applicationId = createdApplication.get("id").asText();
        uploadAllRequiredDocuments(accessToken, applicationId);
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/invalid", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "REASON_B"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Loan applications that have entered servicing cannot be marked invalid."
                ));
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
                                "documentType", "AADHAAR_FILE",
                                "note", "Aadhaar uploaded from partner LOS",
                                "fileName", "aadhaar.pdf",
                                "fileReference", "minio://tenant-apex/aadhaar.pdf",
                                "sourceReference", "los-doc-001",
                                "contentType", "application/pdf"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("AADHAAR_FILE"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.fileName").value("aadhaar.pdf"));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "documentType", "LOAN_AGREEMENT",
                                "note", "Loan agreement uploaded from partner LOS",
                                "fileName", "loan-agreement.pdf",
                                "fileReference", "minio://tenant-apex/loan-agreement.pdf",
                                "sourceReference", "los-doc-002",
                                "contentType", "application/pdf"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("LOAN_AGREEMENT"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.fileName").value("loan-agreement.pdf"));

        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "AWAITING_APPROVAL");
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", systemAdmin());
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);

        JsonNode externalDetail = getApplicationDetail(accessToken, applicationId);
        String loanId = externalDetail.get("loanAccount").get("id").asText();
        recordPaymentViaLsp(accessToken, loanId);

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
    void documentUploadRejectsOversizedFilesAndDisallowedMimeTypesAndReplacesOnReupload() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-AUTO-CHECK-001");
        String applicationId = createdApplication.get("id").asText();

        // 11 MB of bytes — exceeds the 10 MB cap.
        byte[] oversized = new byte[11 * 1024 * 1024];
        java.util.Arrays.fill(oversized, (byte) 'A');
        mockMvc.perform(multipart("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .file(new MockMultipartFile(
                                "file",
                                "huge.pdf",
                                "application/pdf",
                                oversized
                        ))
                        .header("Authorization", "Bearer " + accessToken)
                        .param("documentType", "PAN_CARD")
                        .param("note", "too large"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_FILE_TOO_LARGE"));

        // Disallowed MIME type (application/zip is not in the allowlist).
        mockMvc.perform(multipart("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .file(new MockMultipartFile(
                                "file",
                                "archive.zip",
                                "application/zip",
                                "PKfake-zip".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + accessToken)
                        .param("documentType", "PAN_CARD")
                        .param("note", "wrong mime"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_MIME_NOT_ALLOWED"));

        // Happy-path upload: small PDF passes; row transitions to SUBMITTED.
        mockMvc.perform(multipart("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .file(new MockMultipartFile(
                                "file",
                                "pan-v1.pdf",
                                "application/pdf",
                                "%PDF-1.4 v1 content".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + accessToken)
                        .param("documentType", "PAN_CARD")
                        .param("note", "first upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("PAN_CARD"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.fileName").value("pan-v1.pdf"))
                .andExpect(jsonPath("$.lmsManagedContent").value(true))
                .andExpect(jsonPath("$.reviewReason").doesNotExist())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());

        // Re-upload of the same documentType replaces the previous file.
        mockMvc.perform(multipart("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .file(new MockMultipartFile(
                                "file",
                                "pan-v2.pdf",
                                "application/pdf",
                                "%PDF-1.4 v2 content".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + accessToken)
                        .param("documentType", "PAN_CARD")
                        .param("note", "second upload (replacement)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("PAN_CARD"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.fileName").value("pan-v2.pdf"))
                .andExpect(jsonPath("$.note").value("second upload (replacement)"));
    }

    @Test
    void documentUploadEnforcesPerDocumentTypeConstraints() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Integration");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-TYPE-CHECK-001");
        String applicationId = createdApplication.get("id").asText();

        mockMvc.perform(multipart("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .file(new MockMultipartFile(
                                "file",
                                "agreement.jpg",
                                "image/jpeg",
                                "jpeg-body".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + accessToken)
                        .param("documentType", "LOAN_AGREEMENT")
                        .param("note", "loan agreement must be pdf"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_MIME_NOT_ALLOWED"))
                .andExpect(jsonPath("$.violations[?(@.field == 'documentType')].message").value("LOAN_AGREEMENT"));

        byte[] sixMegabytes = new byte[6 * 1024 * 1024];
        java.util.Arrays.fill(sixMegabytes, (byte) 'P');
        mockMvc.perform(multipart("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .file(new MockMultipartFile(
                                "file",
                                "pan-large.pdf",
                                "application/pdf",
                                sixMegabytes
                        ))
                        .header("Authorization", "Bearer " + accessToken)
                        .param("documentType", "PAN_CARD")
                        .param("note", "pan capped at 5mb"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.violations[?(@.field == 'documentType')].message").value("PAN_CARD"));

        mockMvc.perform(multipart("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .file(new MockMultipartFile(
                                "file",
                                "agreement.pdf",
                                "application/pdf",
                                "%PDF-1.4 agreement".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + accessToken)
                        .param("documentType", "LOAN_AGREEMENT")
                        .param("note", "pdf ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("LOAN_AGREEMENT"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
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

    @Test
    void lspDocumentsListReturnsUploadsOnlyForOwnerWithStatusSubmitted() throws Exception {
        // Gap #4: GET /api/v1/lsp/loan-applications/{id}/documents returns
        // every checklist row that has been submitted (PENDING placeholders
        // hidden), with status folded to SUBMITTED. Cross-tenant access is
        // refused with 400.
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        ProductFixture northProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());
        mapProductToLsp(northProduct.id(), north.id());

        JsonNode apexClient = createApiClient(apex.id(), "Apex Integration");
        JsonNode northClient = createApiClient(north.id(), "North Integration");
        String apexAccessToken = issueClientCredentialsToken(
                apexClient.get("clientId").asText(),
                apexClient.get("clientSecret").asText()
        );
        String northAccessToken = issueClientCredentialsToken(
                northClient.get("clientId").asText(),
                northClient.get("clientSecret").asText()
        );

        JsonNode apexApplication = createExternalApplication(apexAccessToken, apexProduct.id(), "APEX-DOCS-001");
        String applicationId = apexApplication.get("id").asText();

        // No docs submitted yet — the read returns an empty list.
        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .header("Authorization", "Bearer " + apexAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Submit one PAN doc.
        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .header("Authorization", "Bearer " + apexAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "documentType", "PAN_CARD",
                                "note", "PAN uploaded from partner LOS",
                                "fileName", "pan.pdf",
                                "fileReference", "minio://tenant-apex/pan.pdf",
                                "sourceReference", "los-doc-pan",
                                "contentType", "application/pdf"
                        ))))
                .andExpect(status().isOk());

        // The read now surfaces a single row, status folded to SUBMITTED.
        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .header("Authorization", "Bearer " + apexAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentType").value("PAN_CARD"))
                .andExpect(jsonPath("$[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$[0].fileName").value("pan.pdf"))
                .andExpect(jsonPath("$[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$[0].note").value("PAN uploaded from partner LOS"))
                .andExpect(jsonPath("$[0].uploadedAt").exists())
                .andExpect(jsonPath("$[0].uploadedByUsername").exists());

        // North's token cannot read Apex's documents.
        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .header("Authorization", "Bearer " + northAccessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // LSP_UI_READ on the owning tenant can read the documents.
        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}/documents", applicationId)
                        .with(lspUiUser(apex.id(), "Apex Tenant")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SUBMITTED"));
    }

    @Test
    void apiClientCanListOnlyProvisionedActiveProducts() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture visible = createProduct("ACTIVE");
        ProductFixture disabled = createProduct("ACTIVE");
        ProductFixture inactive = createProduct("INACTIVE");
        ProductFixture northOnly = createProduct("ACTIVE");
        mapProductToLsp(visible.id(), apex.id());
        mapProductToLsp(disabled.id(), apex.id());
        mapProductToLsp(inactive.id(), apex.id());
        mapProductToLsp(northOnly.id(), north.id());

        loanProductLspMappingRepository.findByLsp_IdAndLoanProduct_Id(UUID.fromString(apex.id()), UUID.fromString(disabled.id()))
                .ifPresent(mapping -> {
                    mapping.update(false);
                    loanProductLspMappingRepository.save(mapping);
                });

        JsonNode apiClient = createApiClient(apex.id(), "Apex Product Reader");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        mockMvc.perform(get("/api/v1/lsp/products")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(visible.id()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void multipartDocumentUploadsAutoApproveAndCreateLoanAccount() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex STP");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-STP-001");
        String applicationId = createdApplication.get("id").asText();
        uploadAllRequiredDocuments(accessToken, applicationId);

        JsonNode detail = getApplicationDetail(accessToken, applicationId);
        String loanAccountId = detail.get("loanAccount").get("id").asText();

        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_PENDING_DISBURSAL"))
                .andExpect(jsonPath("$.loanAccount.id").value(loanAccountId))
                .andExpect(jsonPath("$.loanAccount.status").value("PENDING_DISBURSEMENT"));
    }

    @Test
    void opsZipDownloadUsesChecklistBackedStoredDocuments() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Zip Download");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-ZIP-001");
        String applicationId = createdApplication.get("id").asText();
        uploadAllRequiredDocuments(accessToken, applicationId);

        MvcResult result = mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/download-all", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();

        byte[] zipBytes = result.getResponse().getContentAsByteArray();
        assertTrue(zipBytes.length > 0);
        assertTrue("application/zip".equals(result.getResponse().getContentType()));

        Set<String> entryNames = new java.util.LinkedHashSet<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }

        assertTrue(entryNames.contains("pan_card/pan_card.pdf"));
        assertTrue(entryNames.contains("loan_agreement/loan_agreement.pdf"));
    }

    @Test
    void lspScheduleValidationAndDisbursementComplianceAreEnforced() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        ProductFixture apexProduct = createProduct("ACTIVE");
        mapProductToLsp(apexProduct.id(), apex.id());

        JsonNode apiClient = createApiClient(apex.id(), "Apex Compliance");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        JsonNode createdApplication = createExternalApplication(accessToken, apexProduct.id(), "APEX-CHECK-001");
        String applicationId = createdApplication.get("id").asText();
        uploadAllRequiredDocuments(accessToken, applicationId);

        mockMvc.perform(put("/api/v1/lsp/loan-applications/{applicationId}/repayment-schedule", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "LSP_PROVIDED",
                                "installments", List.of(Map.of(
                                        "installmentNumber", 1,
                                        "dueDate", LocalDate.now().plusMonths(1).toString(),
                                        "openingPrincipal", "45000.00",
                                        "principalDue", "1000.00",
                                        "interestDue", "100.00",
                                        "installmentAmount", "1000.00",
                                        "closingPrincipal", "44000.00"
                                ))
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("REPAYMENT_SCHEDULE_INVALID"));

        mockMvc.perform(put("/api/v1/lsp/loan-applications/{applicationId}/repayment-schedule", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mode", "GENERATED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12));

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/disbursement", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "disbursalAmount", new BigDecimal("43000.00"),
                                "bankAccountNumber", "123456789012",
                                "ifscCode", "HDFC0001234"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("disbursalAmount"));
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
                                testMobileFor(externalLoanId),
                                "internal+" + externalLoanId.toLowerCase() + "@example.com",
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

    private String testMobileFor(String seed) {
        int numeric = Math.abs(seed.hashCode());
        return String.format("9%09d", numeric % 1_000_000_000);
    }

    private JsonNode createExternalApplication(String accessToken, String productId, String externalLoanId) throws Exception {
        return createExternalApplication(
                accessToken,
                defaultExternalApplicationPayload(extractLspIdFromToken(accessToken), productId, externalLoanId)
        );
    }

    private JsonNode createExternalApplication(String accessToken, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private LinkedHashMap<String, Object> defaultExternalApplicationPayload(
            String lspId,
            String productId,
            String externalLoanId
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("lspLoanId", externalLoanId);
        payload.put("fullName", "Anika Sharma");
        payload.put("emailAddress", "anika@example.com");
        payload.put("mobileNumber", "9999999999");
        payload.put("dob", "1992-03-10");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Ramesh Sharma");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", "ABCDE1234F");
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
        payload.put("accountHolderName", "Anika Sharma");
        payload.put("referencePersonName", "Neha Verma");
        payload.put("referencePersonNumber", "9888877777");
        return payload;
    }

    private JsonNode getApplicationDetail(String accessToken, String applicationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getRepaymentSchedule(String accessToken, String loanId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/lsp/loans/{loanId}/repayment-schedule", loanId)
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

    private void markAllRequiredKycDocumentsVerified(String applicationId) {
        loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
                .forEach(item -> {
                    if (item.isRequired()) {
                        item.update(
                                com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus.SUBMITTED,
                                "Uploaded for approval",
                                "ops.user",
                                item.getDocumentType().name().toLowerCase() + ".pdf",
                                "seed://" + item.getDocumentType().name().toLowerCase(),
                                "seed",
                                "application/pdf"
                        );
                        loanApplicationDocumentChecklistRepository.save(item);
                    }
                });
    }

    private void requestDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private void uploadAllRequiredDocuments(String accessToken, String applicationId) throws Exception {
        List<Map<String, Object>> documentMetadata = new java.util.ArrayList<>();
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder requestBuilder =
                multipart("/api/v1/lsp/loan-applications/{applicationId}/documents/batch", applicationId);
        requestBuilder.header("Authorization", "Bearer " + accessToken);

        for (String documentType : List.of(
                "PAN_CARD",
                "AADHAAR_FILE",
                "ADDRESS_PROOF",
                "INCOME_PROOF",
                "BANK_STATEMENT",
                "SELFIE_PHOTOGRAPH",
                "KFS",
                "LOAN_AGREEMENT"
        )) {
            documentMetadata.add(Map.of(
                    "documentType", documentType,
                    "note", "Uploaded " + documentType,
                    "sourceReference", "src-" + documentType
            ));
            requestBuilder.file(new MockMultipartFile(
                    "files",
                    documentType.toLowerCase() + ".pdf",
                    "application/pdf",
                    ("content-" + documentType).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
        }
        requestBuilder.file(new MockMultipartFile(
                "documents",
                "",
                "application/json",
                objectMapper.writeValueAsBytes(documentMetadata)
        ));

        mockMvc.perform(requestBuilder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].lmsManagedContent").value(true));
    }

    private String extractLspIdFromToken(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
        try {
            return objectMapper.readTree(payload).get("lspId").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to extract lspId from token", exception);
        }
    }

    private void resolveDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());
    }

    private void recordPayment(String applicationId) throws Exception {
        MvcResult scheduleResult = mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/repayment-schedule",
                        applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode schedule = objectMapper.readTree(scheduleResult.getResponse().getContentAsString());
        String installmentId = schedule.get(0).get("id").asText();
        BigDecimal amount = schedule.get(0).get("outstandingAmount").decimalValue();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                        .with(systemAdmin())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetInstallmentId", installmentId,
                                "amount", amount,
                                "postedAt", LocalDate.now().minusDays(1).toString(),
                                "reference", "PAY-LSP-001",
                                "channel", "UPI"
                        ))))
                .andExpect(status().isOk());
    }

    private void recordPaymentViaLsp(String accessToken, String loanId) throws Exception {
        MvcResult scheduleResult = mockMvc.perform(get("/api/v1/lsp/loans/{loanId}/repayment-schedule", loanId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode schedule = objectMapper.readTree(scheduleResult.getResponse().getContentAsString());
        String installmentId = schedule.get(0).get("id").asText();
        BigDecimal amount = schedule.get(0).get("outstandingAmount").decimalValue();

        mockMvc.perform(post("/api/v1/lsp/loans/{loanId}/payments", loanId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetInstallmentId", installmentId,
                                "amount", amount,
                                "postedAt", LocalDate.now().minusDays(1).toString(),
                                "reference", "PAY-LSP-001",
                                "channel", "UPI"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccountId").value(loanId))
                .andExpect(jsonPath("$.targetInstallmentId").value(installmentId))
                .andExpect(jsonPath("$.reference").value("PAY-LSP-001"));
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
                        .content(objectMapper.writeValueAsString(new AuthController.ClientCredentialsRequest(
                                clientId,
                                clientSecret
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

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspUiWriteUser(
            String lspId,
            String lspName
    ) {
        return jwt().jwt(jwt -> jwt
                        .subject("tenant.writer")
                        .claim("roles", List.of("LSP_UI_WRITE"))
                        .claim("lspId", lspId)
                        .claim("lspName", lspName))
                .authorities(() -> "ROLE_LSP_UI_WRITE");
    }
}
