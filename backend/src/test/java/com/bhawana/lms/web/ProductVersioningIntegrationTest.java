package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanProductVersionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.service.AdminReportingService;
import com.bhawana.lms.service.LoanDisbursementCommandService;
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
class ProductVersioningIntegrationTest {

    private static final BigDecimal RATE_A = new BigDecimal("12.00");
    private static final BigDecimal RATE_B = new BigDecimal("24.00");
    private static final BigDecimal FEE_F1 = new BigDecimal("2.25");
    private static final BigDecimal FEE_F2 = new BigDecimal("5.00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanProductVersionRepository loanProductVersionRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Autowired
    private AdminReportingService adminReportingService;

    @Autowired
    private LoanDisbursementCommandService loanDisbursementCommandService;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void creatingProductCreatesVersionOneWithMatchingTerms() throws Exception {
        String productId = createProduct("VER-CREATE", RATE_A, FEE_F1);

        var versions = loanProductVersionRepository.findByLoanProduct_IdOrderByVersionNumberDesc(UUID.fromString(productId));
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0).getVersionNumber());
        assertEquals(0, RATE_A.compareTo(versions.get(0).getInterestRate()));
        assertEquals(0, FEE_F1.compareTo(versions.get(0).getProcessingFeeRate()));
    }

    @Test
    void updatingInterestRateCreatesVersionTwo_nameOnlyDoesNot() throws Exception {
        String productId = createProduct("VER-UPDATE", RATE_A, FEE_F1);

        updateProduct(productId, "VER-UPDATE", "Renamed Product", RATE_B, FEE_F1);
        assertEquals(2, loanProductVersionRepository.findByLoanProduct_IdOrderByVersionNumberDesc(UUID.fromString(productId)).size());

        updateProduct(productId, "VER-UPDATE", "Renamed Again", RATE_B, FEE_F1);
        assertEquals(2, loanProductVersionRepository.findByLoanProduct_IdOrderByVersionNumberDesc(UUID.fromString(productId)).size());
    }

    @Test
    void historicalIntegrityAfterProductRateChange() throws Exception {
        String lspId = createLsp("VER-HIST");
        String productId = createProduct("VER-HIST-PROD", RATE_A, FEE_F1);
        mapProductToLsp(productId, lspId);

        String applicationId = createAndApproveApplication(lspId, productId, "HIST-EXT-001");
        UUID accountId = loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId)).orElseThrow().getId();
        List<LoanRepaymentScheduleInstallment> scheduleBefore = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(accountId);
        BigDecimal emiBefore = scheduleBefore.get(0).getInstallmentAmount();

        updateProduct(productId, "VER-HIST-PROD", "Historical Product", RATE_B, FEE_F1);

        List<LoanRepaymentScheduleInstallment> scheduleAfter = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(accountId);
        assertEquals(emiBefore, scheduleAfter.get(0).getInstallmentAmount());

        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);
        mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interestRate").value(12.00));

        AdminReportingService.PortfolioMisRow misRow = adminReportingService.buildPortfolioMisReport(null, null, null).stream()
                .filter(row -> applicationId.equals(row.applicationId()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, RATE_A.compareTo(misRow.interestRate()));

        String newApplicationId = createApplicationViaOps(lspId, productId, "HIST-EXT-002");
        var newApplication = loanApplicationRepository.findDetailedById(UUID.fromString(newApplicationId)).orElseThrow();
        assertEquals(2, newApplication.getLoanProductVersion().getVersionNumber());
        assertEquals(0, RATE_B.compareTo(newApplication.getLoanProductVersion().getInterestRate()));
    }

    @Test
    void disbursementUsesSnapshottedProcessingFeeRate() throws Exception {
        String lspId = createLsp("VER-FEE");
        String productId = createProduct("VER-FEE-PROD", RATE_A, FEE_F1);
        mapProductToLsp(productId, lspId);

        String applicationId = createAndApproveApplication(lspId, productId, "FEE-EXT-001");
        updateProduct(productId, "VER-FEE-PROD", "Fee Product", RATE_A, FEE_F2);

        loanDisbursementCommandService.initiateDisbursement(UUID.fromString(applicationId), "ops.admin");
        loanDisbursementCommandService.resolveMockDisbursementOutcome(
                UUID.fromString(applicationId),
                "ops.admin",
                MockDisbursementOutcome.DISBURSED
        );

        var loanAccount = loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId)).orElseThrow();
        BigDecimal expectedFee = new BigDecimal("1012.50");
        assertEquals(0, expectedFee.compareTo(loanAccount.getProcessingFeeAmount()));
    }

    private String createAndApproveApplication(String lspId, String productId, String externalLoanId) throws Exception {
        String applicationId = createApplicationViaOps(lspId, productId, externalLoanId);
        transitionToAwaitingApproval(applicationId);
        markKycComplete(applicationId);
        transitionToApproved(applicationId);
        assertNotNull(loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId)).orElse(null));
        return applicationId;
    }

    private String createProduct(String code, BigDecimal interestRate, BigDecimal processingFeeRate) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Version test " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", interestRate,
                                "processingFeeRate", processingFeeRate,
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void updateProduct(
            String productId,
            String code,
            String name,
            BigDecimal interestRate,
            BigDecimal processingFeeRate
    ) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/products/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", name,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", interestRate,
                                "processingFeeRate", processingFeeRate,
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLsp(String codeSuffix) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + codeSuffix,
                                "name", "Version LSP " + codeSuffix,
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

    private String createApplicationViaOps(String lspId, String productId, String externalLoanId) throws Exception {
        String borrowerPan = uniquePan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Version Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "version+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1990, 1, 1));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("50000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
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
                                "note", "Approved for version test"
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
                            "Uploaded for version test",
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

    private JsonNode createApiClient(String lspId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Version test client",
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
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", apiClient.get("clientId").asText(),
                                "clientSecret", apiClient.get("clientSecret").asText()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
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

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
