package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementOutcomeAudit;
import com.bhawana.lms.domain.DisbursementOutcomeAuditOutcome;
import com.bhawana.lms.domain.DisbursementOutcomeAuditSource;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.repo.DisbursementOutcomeAuditRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
class LoanApplicationOpsControllerMockOutcomeAuditTest {

    private static final String CLIENT_IP = "198.51.100.22";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DisbursementOutcomeAuditRepository disbursementOutcomeAuditRepository;

    @Autowired
    private LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;

    @Autowired
    private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Test
    void mockOutcomeWritesDisbursementOutcomeAuditRowWithActorIp() throws Exception {
        UUID applicationId = seedDisbursementRequestedApplication();
        long before = disbursementOutcomeAuditRepository.count();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .header("X-Correlation-Id", "corr-mock-outcome-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());

        assertEquals(before + 1, disbursementOutcomeAuditRepository.count());
        DisbursementOutcomeAudit audit = disbursementOutcomeAuditRepository.findAll().stream()
                .filter(row -> row.getLoanApplicationId().equals(applicationId))
                .findFirst()
                .orElseThrow();

        assertEquals("ops.admin", audit.getActorUsername());
        assertEquals(CLIENT_IP, audit.getActorIp());
        assertEquals("corr-mock-outcome-1", audit.getCorrelationId());
        assertEquals(DisbursementOutcomeAuditSource.MOCK_OUTCOME_ENDPOINT, audit.getSource());
        assertEquals(DisbursementOutcomeAuditOutcome.DISBURSED, audit.getOutcome());

        LoanDisbursementRequestLog latestLog = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(audit.getLoanAccountId())
                .orElseThrow();
        assertEquals(latestLog.getProviderRequestId(), audit.getProviderRequestId());
    }

    @Test
    void mockOutcomeStillWritesStatusTransitionAuditRow() throws Exception {
        UUID applicationId = seedDisbursementRequestedApplication();
        long statusTransitionsBefore = loanApplicationAuditEventRepository.findAll().stream()
                .filter(event -> event.getLoanApplication().getId().equals(applicationId))
                .filter(event -> event.getAction() == LoanApplicationAuditAction.STATUS_TRANSITION)
                .count();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());

        long statusTransitionsAfter = loanApplicationAuditEventRepository.findAll().stream()
                .filter(event -> event.getLoanApplication().getId().equals(applicationId))
                .filter(event -> event.getAction() == LoanApplicationAuditAction.STATUS_TRANSITION)
                .count();
        assertEquals(statusTransitionsBefore + 1, statusTransitionsAfter);
    }

    @ParameterizedTest
    @EnumSource(value = DisbursementOutcomeAuditOutcome.class)
    void mockOutcomeWritesMatchingOutcomeEnum(DisbursementOutcomeAuditOutcome expectedOutcome) throws Exception {
        UUID applicationId = seedDisbursementRequestedApplication();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", expectedOutcome.name()))))
                .andExpect(status().isOk());

        DisbursementOutcomeAudit audit = disbursementOutcomeAuditRepository.findAll().stream()
                .filter(row -> row.getLoanApplicationId().equals(applicationId))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals(expectedOutcome, audit.getOutcome());
    }

    @Test
    void mockOutcomeWhenLoanNotDisbursementRequestedWritesNoAuditRow() throws Exception {
        UUID applicationId = createInitializedApplication();
        long before = disbursementOutcomeAuditRepository.count();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isBadRequest());

        assertEquals(before, disbursementOutcomeAuditRepository.count());
    }

    private UUID seedDisbursementRequestedApplication() throws Exception {
        UUID applicationId = createInitializedApplication();
        approveForDisbursement(applicationId);
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        return applicationId;
    }

    private UUID createInitializedApplication() throws Exception {
        String lspId = createLsp();
        String productId = createProduct();
        mapProductToLsp(productId, lspId);
        String pan = randomPan();
        JsonNode application = createApplication(lspId, productId, "MOCK-OUT-" + UUID.randomUUID(), pan);
        return UUID.fromString(application.get("id").asText());
    }

    private void approveForDisbursement(UUID applicationId) throws Exception {
        transition(applicationId, "AWAITING_APPROVAL", "Ready");
        markAllRequiredKycDocumentsVerified(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved");
    }

    private String createLsp() throws Exception {
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "MOA" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                                "name", "Mock Outcome LSP",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(lspResult.getResponse().getContentAsString()).get("id").asText();
    }

    private String createProduct() throws Exception {
        MvcResult productResult = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "MOAP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(),
                                "name", "Mock Outcome Product",
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", new BigDecimal("18.00"),
                                "processingFeeRate", new BigDecimal("2.00"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(productResult.getResponse().getContentAsString()).get("id").asText();
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private JsonNode createApplication(String lspId, String productId, String externalLoanId, String borrowerPan)
            throws Exception {
        MvcResult appResult = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lspId, productId, externalLoanId, borrowerPan))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(appResult.getResponse().getContentAsString());
    }

    private void transition(UUID applicationId, String targetStatus, String note) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", note
                        ))))
                .andExpect(status().isOk());
    }

    private void markAllRequiredKycDocumentsVerified(UUID applicationId) {
        loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .forEach(item -> {
                    if (item.isRequired()) {
                        item.update(
                                LoanApplicationDocumentChecklistStatus.SUBMITTED,
                                "Uploaded for mock-outcome audit test",
                                "ops.user",
                                item.getDocumentType().name().toLowerCase() + ".pdf",
                                "doc/" + item.getDocumentType().name().toLowerCase(),
                                "seed",
                                "application/pdf"
                        );
                        loanApplicationDocumentChecklistRepository.save(item);
                    }
                });
    }

    private static Map<String, Object> loanApplicationPayload(
            String lspId,
            String productId,
            String externalLoanId,
            String borrowerPan
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Mock Outcome Borrower");
        payload.put("borrowerMobile", "9" + String.format("%09d", Math.abs(borrowerPan.hashCode()) % 1_000_000_000));
        payload.put("borrowerEmail", "mock-outcome+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1992, 3, 10));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("78000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);
        return payload;
    }

    private static String randomPan() {
        String hex = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            char c = hex.charAt(i);
            int idx = (c >= '0' && c <= '9') ? (c - '0') : (10 + (c - 'A'));
            letters.append((char) ('A' + idx));
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 5; i < 9; i++) {
            char c = hex.charAt(i);
            int idx = (c >= '0' && c <= '9') ? (c - '0') : (10 + (c - 'A'));
            digits.append((char) ('0' + (idx % 10)));
        }
        char tail = hex.charAt(9);
        char lastLetter = (tail >= '0' && tail <= '9') ? (char) ('A' + (tail - '0')) : (char) ('A' + 10 + (tail - 'A'));
        return letters.toString() + digits + lastLetter;
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(token -> token.subject("prod.admin").claim("roles", List.of("PRODUCT_ADMIN", "SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN", () -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
