package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository;
import com.bhawana.lms.repo.BorrowerPiiRevealAuditRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import com.bhawana.lms.service.LoanDisbursementWorkerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class Issue62BorrowerBankDetailsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BorrowerBankDetailsUpdateAuditRepository bankDetailsUpdateAuditRepository;

    @Autowired
    private BorrowerPiiRevealAuditRepository borrowerPiiRevealAuditRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private WebhookEventOutboxRepository webhookEventOutboxRepository;

    @Autowired
    private LoanDisbursementWorkerService loanDisbursementWorkerService;

    @Test
    void borrowerBankDetailsUpdateViaDedicatedEndpointIsAuditedAndWebhookFires() throws Exception {
        String lspId = createLspViaAdmin("BANK-LSP");
        enableWebhook(lspId, List.of("BORROWER_BANK_DETAILS_UPDATED"));
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);

        JsonNode application = createApplicationViaLsp(accessToken, lspId, productId, "BANK-EXT-001");
        String borrowerId = application.get("borrowerId").asText();

        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "998877665544",
                                "bankName", "Updated Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "Worker Borrower"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountNumber").value("998877665544"))
                .andExpect(jsonPath("$.ifscCode").value("HDFC0001234"));

        mockMvc.perform(get("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountNumber").value("998877665544"));

        assertTrue(
                bankDetailsUpdateAuditRepository.findAll().stream()
                        .anyMatch(audit -> "998877665544".equals(audit.getNewBankAccountNumber()))
        );

        List<WebhookEventOutbox> events = webhookEventOutboxRepository.findTop50ByLsp_IdOrderByCreatedAtDesc(
                UUID.fromString(lspId)
        );
        assertTrue(events.stream().anyMatch(event -> event.getEventType() == WebhookEventType.BORROWER_BANK_DETAILS_UPDATED));
    }

    @Test
    void bankDetailsGetRecordsPiiRevealAuditWithoutStoringAccountNumber() throws Exception {
        borrowerPiiRevealAuditRepository.deleteAllInBatch();
        String lspId = createLspViaAdmin("REVEAL-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);
        String expectedActor = apiClient.get("clientId").asText();

        JsonNode application = createApplicationViaLsp(accessToken, lspId, productId, "REVEAL-EXT-001");
        String borrowerId = application.get("borrowerId").asText();

        mockMvc.perform(get("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountNumber").value("123456789012"));

        assertEquals(1, borrowerPiiRevealAuditRepository.countByBorrower_Id(UUID.fromString(borrowerId)));
        var audit = borrowerPiiRevealAuditRepository.findAll().stream()
                .filter(row -> row.getBorrower().getId().equals(UUID.fromString(borrowerId)))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedActor, audit.getActorUsername());
        assertEquals("LSP_API_CLIENT", audit.getActorType());
        assertEquals(UUID.fromString(lspId), audit.getLsp().getId());
        assertEquals("bankAccountNumber,ifscCode,accountHolderName,bankName", audit.getRevealedFields());
        assertFalse(audit.getRevealedFields().contains("123456789012"));
    }

    @Test
    void disbursementProceedsImmediatelyAfterBankDetailsUpdateNoCooldown() throws Exception {
        String lspId = createLspViaAdmin("COOLDOWN-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);

        String applicationId = seedApprovedApplication(lspId, productId);
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();

        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "112233445566",
                                "bankName", "Cooldown Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "Worker Borrower"
                        ))))
                .andExpect(status().isOk());

        loanDisbursementWorkerService.processApplication(UUID.fromString(applicationId));
        assertEquals(
                LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow().getStatus()
        );
    }

    @Test
    void disbursementBankCheckRejectsMismatchedDetailsWithoutMutatingProfile() throws Exception {
        String lspId = createLspViaAdmin("MISMATCH-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);

        String applicationId = seedApprovedApplication(lspId, productId);
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();

        mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check", applicationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "disbursalAmount", new BigDecimal("45000.00"),
                                "bankAccountNumber", "000000000001",
                                "ifscCode", "HDFC0001234"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountNumber").value("123456789012"));
    }

    @Test
    void repeatedBankDetailMismatchesFireLspBoundViolationAlert() throws Exception {
        opsAlertRepository.deleteAllInBatch();
        String lspId = createLspViaAdmin("FISH-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);
        String applicationId = seedApprovedApplication(lspId, productId);

        Map<String, Object> wrongBank = Map.of(
                "disbursalAmount", new BigDecimal("45000.00"),
                "bankAccountNumber", "000000000099",
                "ifscCode", "HDFC0001234"
        );
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/v1/lsp/loan-applications/{applicationId}/disbursement-bank-check", applicationId)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongBank)))
                    .andExpect(status().isUnprocessableEntity());
        }

        assertTrue(
                opsAlertRepository.findAll().stream()
                        .anyMatch(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION)
        );
    }

    @Test
    void lspPatchBlockedAfterDisbursementWhenNoPreDisbursalApplication() throws Exception {
        String lspId = createLspViaAdmin("POST-DISB-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);

        String applicationId = seedApprovedApplication(lspId, productId);
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();

        loanDisbursementWorkerService.processApplication(UUID.fromString(applicationId));
        assertEquals(
                LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow().getStatus()
        );

        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "556677889900",
                                "bankName", "Post Disbursal Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "Worker Borrower"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BANK_DETAILS_UPDATE_NOT_ALLOWED"));
    }

    @Test
    void lspPatchBlockedWhileDisbursementInFlight() throws Exception {
        String lspId = createLspViaAdmin("INFLIGHT-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);

        String applicationId = seedApprovedApplication(lspId, productId, "MOCK0PENDOK");
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();

        loanDisbursementWorkerService.processApplication(UUID.fromString(applicationId));
        assertEquals(
                LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId)).orElseThrow().getStatus()
        );

        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "667788990011",
                                "bankName", "In Flight Bank",
                                "ifscCode", "MOCK0PENDOK",
                                "accountHolderName", "Worker Borrower"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT"));
    }

    @Test
    void adminPatchSucceedsAfterDisbursement() throws Exception {
        String lspId = createLspViaAdmin("ADMIN-POST-DISB-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);

        String applicationId = seedApprovedApplication(lspId, productId);
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();

        loanDisbursementWorkerService.processApplication(UUID.fromString(applicationId));
        assertEquals(
                LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow().getStatus()
        );

        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "999988887777",
                                "bankName", "Admin Corrected Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "Worker Borrower"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountNumber").value("999988887777"));
    }

    @Test
    void adminPatchBlockedWhileDisbursementInFlight() throws Exception {
        String lspId = createLspViaAdmin("ADMIN-INFLIGHT-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);

        String applicationId = seedApprovedApplication(lspId, productId, "MOCK0PENDOK");
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();

        loanDisbursementWorkerService.processApplication(UUID.fromString(applicationId));
        assertEquals(
                LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId)).orElseThrow().getStatus()
        );

        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "888877776666",
                                "bankName", "Admin In Flight Bank",
                                "ifscCode", "MOCK0PENDOK",
                                "accountHolderName", "Worker Borrower"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BANK_DETAILS_LOCKED_DISBURSEMENT_IN_FLIGHT"));
    }

    @Test
    void repeatedBorrowerBankDetailUpdatesFireVelocityAlertButStillSucceed() throws Exception {
        opsAlertRepository.deleteAllInBatch();
        String lspId = createLspViaAdmin("VELOCITY-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);

        JsonNode application = createApplicationViaLsp(accessToken, lspId, productId, "VEL-EXT-001");
        String borrowerId = application.get("borrowerId").asText();

        patchBankDetails(accessToken, borrowerId, "111111111111");
        patchBankDetails(accessToken, borrowerId, "222222222222");

        assertTrue(
                opsAlertRepository.findAll().stream()
                        .anyMatch(alert -> alert.getType() == OpsAlertType.BORROWER_BANK_DETAILS_VELOCITY)
        );

        mockMvc.perform(get("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountNumber").value("222222222222"));
    }

    private void patchBankDetails(String accessToken, String borrowerId, String accountNumber) throws Exception {
        patchBankDetails(accessToken, borrowerId, accountNumber, "HDFC0001234");
    }

    private void patchBankDetails(
            String accessToken,
            String borrowerId,
            String accountNumber,
            String ifscCode
    ) throws Exception {
        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", accountNumber,
                                "bankName", "Velocity Bank",
                                "ifscCode", ifscCode,
                                "accountHolderName", "Worker Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String seedApprovedApplication(String lspId, String productId) throws Exception {
        return seedApprovedApplication(lspId, productId, "HDFC0001234");
    }

    private String seedApprovedApplication(String lspId, String productId, String ifscCode) throws Exception {
        String applicationId = createApplicationViaOps(lspId, productId);
        transitionToAwaitingApproval(applicationId);
        markKycComplete(applicationId);
        transitionToApproved(applicationId);

        JsonNode apiClient = createApiClient(lspId);
        String accessToken = issueToken(apiClient);
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();
        patchBankDetails(accessToken, borrowerId, "123456789012", ifscCode);
        return applicationId;
    }

    private String createApplicationViaOps(String lspId, String productId) throws Exception {
        String borrowerPan = uniquePan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", "EXT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Worker Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "worker+" + borrowerPan.toLowerCase() + "@example.com");
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

    private JsonNode createApplicationViaLsp(
            String accessToken,
            String lspId,
            String productId,
            String externalLoanId
    ) throws Exception {
        String pan = uniquePan();
        Map<String, Object> payload = defaultExternalApplicationPayload(lspId, productId, externalLoanId, pan);

        MvcResult result = mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void enableWebhook(String lspId, List<String> eventTypes) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://example.com/webhooks",
                                "signingSecret", "test-signing-secret",
                                "eventTypes", eventTypes
                        ))))
                .andExpect(status().isOk());
    }

    private JsonNode createApiClient(String lspId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Bank details client",
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
                                "name", "Bank LSP " + codeSuffix,
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
                                "name", "Bank product " + code,
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
                                "note", "Approved for bank-details test"
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
                            "Uploaded for bank-details test",
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
        payload.put("fullName", "Worker Borrower");
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
        payload.put("accountHolderName", "Worker Borrower");
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

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
