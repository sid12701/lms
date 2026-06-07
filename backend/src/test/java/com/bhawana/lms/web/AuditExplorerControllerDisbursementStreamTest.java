package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
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
class AuditExplorerControllerDisbursementStreamTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Test
    void disbursementStreamSurfacesMockOutcomeRow() throws Exception {
        UUID applicationId = disburseApplication();

        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "DISBURSEMENT")
                        .queryParam("loanApplicationId", applicationId.toString())
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].stream").value("DISBURSEMENT"))
                .andExpect(jsonPath("$.items[0].action").value("MOCK_OUTCOME_DISBURSED"))
                .andExpect(jsonPath("$.items[0].loanApplicationId").value(applicationId.toString()))
                .andExpect(jsonPath("$.items[0].detail.outcome").value("DISBURSED"))
                .andExpect(jsonPath("$.items[0].detail.source").value("MOCK_OUTCOME_ENDPOINT"));
    }

    private UUID disburseApplication() throws Exception {
        String lspId = createLsp();
        String productId = createProduct();
        mapProductToLsp(productId, lspId);
        String pan = randomPan();
        UUID applicationId = UUID.fromString(
                createApplication(lspId, productId, "DEX-" + UUID.randomUUID(), pan).get("id").asText());

        transition(applicationId, "AWAITING_APPROVAL", "Ready");
        markAllRequiredKycDocumentsVerified(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved");
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());
        return applicationId;
    }

    private String createLsp() throws Exception {
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "DEX" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                                "name", "Disbursement Explorer LSP",
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
                                "code", "DEXP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(),
                                "name", "Disbursement Explorer Product",
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
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
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
                                "Uploaded for disbursement explorer test",
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
        payload.put("borrowerFullName", "Disbursement Explorer Borrower");
        payload.put("borrowerMobile", "9" + String.format("%09d", Math.abs(borrowerPan.hashCode()) % 1_000_000_000));
        payload.put("borrowerEmail", "disbursement-explorer+" + borrowerPan.toLowerCase() + "@example.com");
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
