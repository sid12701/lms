package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementBankMismatchLogRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.service.DisbursementPreflightValidator;
import com.bhawana.lms.service.LoanDisbursementWorkerService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class Issue125BankDetailHolderNameMatchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanDisbursementBankMismatchLogRepository bankMismatchLogRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private LoanDisbursementWorkerService loanDisbursementWorkerService;

    @Test
    void preflight_returns_OK_with_no_warnings_when_holder_name_matches_exactly() throws Exception {
        Fixture fixture = seedApprovedFixture("EXACT-MATCH");
        patchBankDetails(fixture, "123456789012", "Worker Borrower");

        mockMvc.perform(post(
                        "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check",
                        fixture.applicationId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disbursementCheckPayload("123456789012", "Worker Borrower")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void preflight_returns_WARN_when_holder_name_differs_by_honorific_prefix() throws Exception {
        Fixture fixture = seedApprovedFixture("HONORIFIC-WARN");
        patchBankDetails(fixture, "123456789012", "JOHN K");

        mockMvc.perform(post(
                        "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check",
                        fixture.applicationId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disbursementCheckPayload("123456789012", "MR. JOHN K")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WARN"))
                .andExpect(jsonPath("$.warnings[0].code").value(DisbursementPreflightValidator.HOLDER_NAME_SOFT_MISMATCH_CODE));
    }

    @Test
    void preflight_returns_422_when_account_number_mismatches_regardless_of_holder_name() throws Exception {
        Fixture fixture = seedApprovedFixture("ACCOUNT-HARD");
        patchBankDetails(fixture, "123456789012", "Worker Borrower");

        mockMvc.perform(post(
                        "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check",
                        fixture.applicationId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disbursementCheckPayload("000000000001", "Worker Borrower")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_VALIDATION_FAILED"));
    }

    @Test
    void preflight_returns_OK_when_holder_name_differs_only_by_internal_whitespace() throws Exception {
        Fixture fixture = seedApprovedFixture("SPACE-OK");
        patchBankDetails(fixture, "123456789012", "JOHN KUMAR");

        mockMvc.perform(post(
                        "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check",
                        fixture.applicationId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disbursementCheckPayload("123456789012", "JOHN  KUMAR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void preflight_returns_OK_when_holder_name_differs_only_by_unicode_form() throws Exception {
        Fixture fixture = seedApprovedFixture("UNICODE-OK");
        String holder = "JÖHN KUMAR";
        patchBankDetails(fixture, "123456789012", holder);

        String alternateForm = Normalizer.normalize(holder, Normalizer.Form.NFD);
        mockMvc.perform(post(
                        "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check",
                        fixture.applicationId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disbursementCheckPayload("123456789012", alternateForm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void preflight_returns_422_when_holder_name_is_initial_expansion() throws Exception {
        Fixture fixture = seedApprovedFixture("INITIAL-HARD");
        patchBankDetails(fixture, "123456789012", "John Kumar");

        mockMvc.perform(post(
                        "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check",
                        fixture.applicationId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disbursementCheckPayload("123456789012", "John K")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("accountHolderName"));
    }

    @Test
    void worker_disburses_only_when_holder_name_soft_mismatch_is_honorific_only() throws Exception {
        opsAlertRepository.deleteAllInBatch();
        bankMismatchLogRepository.deleteAllInBatch();
        Fixture fixture = seedApprovedFixtureWithFullName("MR. John K", "SOFT-WORKER");
        patchBankDetails(fixture, "123456789012", "John K");

        loanDisbursementWorkerService.processApplication(UUID.fromString(fixture.applicationId()));

        assertEquals(
                LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(UUID.fromString(fixture.applicationId())).orElseThrow().getStatus()
        );
        assertTrue(
                bankMismatchLogRepository.findAll().stream().anyMatch(log -> log.isSoft())
        );
        assertTrue(
                opsAlertRepository.findAll().stream()
                        .anyMatch(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION
                                && alert.getContextJson() != null
                                && alert.getContextJson().contains("HOLDER_NAME_SOFT_MISMATCH"))
        );
    }

    @Test
    void worker_rejects_when_holder_name_is_initial_expansion() throws Exception {
        bankMismatchLogRepository.deleteAllInBatch();
        Fixture fixture = seedApprovedFixtureWithFullName("John K", "INITIAL-WORKER");
        patchBankDetails(fixture, "123456789012", "John Kumar");

        loanDisbursementWorkerService.processApplication(UUID.fromString(fixture.applicationId()));

        assertEquals(
                LoanApplicationStatus.REJECTED,
                loanApplicationRepository.findById(UUID.fromString(fixture.applicationId())).orElseThrow().getStatus()
        );
        assertFalse(bankMismatchLogRepository.findAll().stream().anyMatch(log -> log.isSoft()));
    }

    @Test
    void worker_does_not_disburse_when_borrower_bank_missing() throws Exception {
        Fixture fixture = seedApprovedFixtureWithFullName("Worker Borrower", "NO-BANK");
        loanDisbursementWorkerService.processApplication(UUID.fromString(fixture.applicationId()));
        assertEquals(
                LoanApplicationStatus.REJECTED,
                loanApplicationRepository.findById(UUID.fromString(fixture.applicationId())).orElseThrow().getStatus()
        );
        assertFalse(bankMismatchLogRepository.findAll().stream().anyMatch(log -> !log.isSoft()));
    }

    @Test
    void holder_name_soft_mismatch_does_not_trigger_strict_BANK_DETAIL_MISMATCH_threshold() throws Exception {
        opsAlertRepository.deleteAllInBatch();
        Fixture fixture = seedApprovedFixture("SOFT-NO-THRESHOLD");
        patchBankDetails(fixture, "123456789012", "JOHN K");

        String payload = disbursementCheckPayload("123456789012", "MR. JOHN K");
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post(
                            "/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check",
                            fixture.applicationId())
                            .header("Authorization", "Bearer " + fixture.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("WARN"));
        }

        assertFalse(
                opsAlertRepository.findAll().stream()
                        .anyMatch(alert -> alert.getContextJson() != null
                                && alert.getContextJson().contains("BANK_DETAIL_MISMATCH"))
        );
    }

    private record Fixture(String applicationId, String accessToken, String borrowerId) {
    }

    private Fixture seedApprovedFixture(String suffix) throws Exception {
        return seedApprovedFixtureWithFullName("Worker Borrower", suffix);
    }

    private Fixture seedApprovedFixtureWithFullName(String fullName, String suffix) throws Exception {
        String lspId = createLspViaAdmin(suffix);
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);

        String applicationId = createApplicationViaOps(lspId, productId, fullName);
        transitionToAwaitingApproval(applicationId);
        markKycComplete(applicationId);
        transitionToApproved(applicationId);

        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();
        return new Fixture(applicationId, accessToken, borrowerId);
    }

    private void patchBankDetails(Fixture fixture, String accountNumber, String accountHolderName) throws Exception {
        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", fixture.borrowerId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", accountNumber,
                                "bankName", "Match Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", accountHolderName
                        ))))
                .andExpect(status().isOk());
    }

    private String disbursementCheckPayload(String accountNumber, String accountHolderName) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "disbursalAmount", new BigDecimal("45000.00"),
                "bankAccountNumber", accountNumber,
                "ifscCode", "HDFC0001234",
                "accountHolderName", accountHolderName
        ));
    }

    private String createApplicationViaOps(String lspId, String productId, String fullName) throws Exception {
        String borrowerPan = uniquePan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", "EXT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", fullName);
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "holder+" + borrowerPan.toLowerCase() + "@example.com");
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

    private JsonNode createApiClient(String lspId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Holder match client",
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

    private String createLspViaAdmin(String codeSuffix) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + codeSuffix,
                                "name", "Holder LSP " + codeSuffix,
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
                                "name", "Holder product " + code,
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
                                "note", "Approved for holder-name test"
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
                            "Uploaded for holder-name test",
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

    private static String uniquePan() {
        return TestPanSequence.uniquePan();
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
