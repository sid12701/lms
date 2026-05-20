package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.bhawana.lms.repo.ReportRequestRepository;
import com.bhawana.lms.service.ReportRequestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "app.reports.notifications.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;

    @Autowired
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Autowired
    private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

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
    private LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanProductAuditEventRepository loanProductAuditEventRepository;

    @Autowired
    private LoanProductLspMappingRepository loanProductLspMappingRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReportRequestService reportRequestService;

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @MockitoBean
    private JavaMailSender javaMailSender;

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
        loanProductAuditEventRepository.deleteAllInBatch();
        loanProductLspMappingRepository.deleteAllInBatch();
        loanProductRepository.deleteAllInBatch();
        reportRequestRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }

    @Test
    void systemAdminCanDownloadPortfolioMisForAllLspsAndFilterByLspAndDisbursalDate() throws Exception {
        LspFixture apex = createLsp("APEX");
        LspFixture north = createLsp("NORTH");
        ProductFixture product = createProduct();
        mapProductToLsps(product.id(), apex.id(), north.id());

        JsonNode apexLoan = createApplication(apex.id(), product.id(), "APEX-LOAN-001", "ABCDE1234F");
        JsonNode northLoan = createApplication(north.id(), product.id(), "NORTH-LOAN-001", "ZXCVB1234N");

        approveLoan(apexLoan.get("id").asText());
        approveLoan(northLoan.get("id").asText());
        disburseLoan(apexLoan.get("id").asText());
        disburseLoan(northLoan.get("id").asText());

        setDisbursedAt(apexLoan.get("id").asText(), LocalDate.of(2026, 3, 10));
        setDisbursedAt(northLoan.get("id").asText(), LocalDate.of(2026, 4, 1));

        MvcResult allResult = mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("portfolio-mis-")))
                .andReturn();

        String allCsv = allResult.getResponse().getContentAsString();
        assertTrue(allCsv.contains("APEX"));
        assertTrue(allCsv.contains("NORTH"));
        assertTrue(allCsv.contains("APEX-LOAN-001"));
        assertTrue(allCsv.contains("NORTH-LOAN-001"));
        assertTrue(allCsv.contains("2026-03-10"));
        assertTrue(allCsv.contains("2026-04-01"));

        String apexFilteredCsv = mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis")
                        .with(systemAdmin())
                        .queryParam("lspId", apex.id())
                        .queryParam("disbursalDateFrom", "2026-03-01")
                        .queryParam("disbursalDateTo", "2026-03-31"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(apexFilteredCsv.contains("APEX-LOAN-001"));
        assertFalse(apexFilteredCsv.contains("NORTH-LOAN-001"));
        assertTrue(apexFilteredCsv.contains("2026-03-10"));
        assertFalse(apexFilteredCsv.contains("2026-04-01"));
    }

    @Test
    void invalidDateRangeAndNonAdminAccessAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis")
                        .with(systemAdmin())
                        .queryParam("disbursalDateFrom", "2026-04-10")
                        .queryParam("disbursalDateTo", "2026-04-01"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis")
                        .with(opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemAdminCanCreateProcessListAndDownloadPortfolioMisRequests() throws Exception {
        LspFixture apex = createLsp("APEX");
        ProductFixture product = createProduct();
        mapProductToLsps(product.id(), apex.id());

        JsonNode apexLoan = createApplication(apex.id(), product.id(), "APEX-ASYNC-001", "ABCDE1234F");
        approveLoan(apexLoan.get("id").asText());
        disburseLoan(apexLoan.get("id").asText());
        setDisbursedAt(apexLoan.get("id").asText(), LocalDate.of(2026, 3, 10));

        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/reports/portfolio-mis/requests")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", apex.id(),
                                "disbursalDateFrom", "2026-03-01",
                                "disbursalDateTo", "2026-03-31",
                                "recipientEmail", "reports@example.com"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedByUsername").value("ops.admin"))
                .andExpect(jsonPath("$.lspId").value(apex.id()))
                .andExpect(jsonPath("$.notificationEmail").value("reports@example.com"))
                .andReturn();

        String requestId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/internal/reports/requests")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        reportRequestService.processPendingRequests(10);

        mockMvc.perform(get("/api/v1/internal/reports/requests")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].notificationEmail").value("reports@example.com"))
                .andExpect(jsonPath("$[0].notificationSentAt").isNotEmpty())
                .andExpect(jsonPath("$[0].fileName").value(org.hamcrest.Matchers.containsString("portfolio-mis-")));

        mockMvc.perform(get("/api/v1/internal/reports/requests/{requestId}/download", requestId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("portfolio-mis-")))
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().contains("APEX-ASYNC-001")));

        verify(javaMailSender).send(any(SimpleMailMessage.class));
    }

    private ProductFixture createProduct() throws Exception {
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
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        return new ProductFixture(createdJson.get("id").asText());
    }

    private LspFixture createLsp(String prefix) throws Exception {
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return new LspFixture(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void mapProductToLsps(String productId, String... lspIds) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspIds)))))
                .andExpect(status().isOk());
    }

    private JsonNode createApplication(String lspId, String productId, String externalLoanId, String borrowerPan)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lspId,
                                productId,
                                externalLoanId,
                                borrowerPan
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void approveLoan(String applicationId) throws Exception {
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for approval", opsUser());
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for report coverage", systemAdmin());
    }

    private void disburseLoan(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());
    }

    private void setDisbursedAt(String applicationId, LocalDate disbursedDate) {
        jdbcTemplate.update(
                "update loan_account set disbursed_at = ? where loan_application_id = ?",
                Timestamp.from(OffsetDateTime.of(disbursedDate.atStartOfDay(), ZoneOffset.UTC).toInstant()),
                UUID.fromString(applicationId)
        );
    }

    private void transitionApplication(
            String applicationId,
            String targetStatus,
            String note,
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor actor
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetStatus", targetStatus);
        payload.put("note", note);

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    private void markAllRequiredKycDocumentsVerified(String applicationId) {
        loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
                .forEach(item -> {
                    if (item.isRequired()) {
                        item.update(
                                com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus.VERIFIED,
                                "Verified for report flow",
                                "ops.user",
                                item.getFileName(),
                                item.getFileReference(),
                                item.getSourceReference(),
                                item.getContentType(),
                                "Matches borrower records",
                                null
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
        payload.put("borrowerFullName", "Anika Sharma");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "anika+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1992, 3, 10));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("78000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);
        return payload;
    }

    private static String mobileForPan(String pan) {
        int hash = Math.abs(pan.hashCode());
        String suffix = String.format("%09d", hash % 1_000_000_000);
        return "9" + suffix;
    }

    private record ProductFixture(String id) {
    }

    private record LspFixture(String id) {
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
