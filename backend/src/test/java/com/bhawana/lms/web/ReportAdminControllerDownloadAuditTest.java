package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.ReportAccessAudit;
import com.bhawana.lms.domain.ReportAccessAuditAction;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.ReportAccessAuditRepository;
import com.bhawana.lms.service.ReportRequestService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.MinioTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "app.reports.notifications.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportAdminControllerDownloadAuditTest extends MinioTestSupport {

    private static final String CLIENT_IP = "203.0.113.77";
    private static final String ADMIN_SUBJECT = "ops.admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private ReportAccessAuditRepository reportAccessAuditRepository;

    @Autowired
    private ReportRequestService reportRequestService;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void syncCsvDownloadWritesMisCsvDownloadedAuditRow() throws Exception {
        LspFixture apex = createLsp("APEX");
        ProductFixture product = createProduct();
        mapProductToLsps(product.id(), apex.id());

        JsonNode loan = createApplication(apex.id(), product.id(), "APEX-AUDIT-001", "ABCDE1234F");
        approveLoan(loan.get("id").asText());
        disburseLoan(loan.get("id").asText());
        setDisbursedAt(loan.get("id").asText(), LocalDate.of(2026, 3, 10));

        MvcResult downloadResult = mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis")
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .queryParam("lspId", apex.id())
                        .queryParam("disbursalDateFrom", "2026-03-01")
                        .queryParam("disbursalDateTo", "2026-03-31"))
                .andExpect(status().isOk())
                .andReturn();

        int byteCount = downloadResult.getResponse().getContentAsByteArray().length;
        ReportAccessAudit audit = latestAuditRow();

        assertEquals(ReportAccessAuditAction.MIS_CSV_DOWNLOADED, audit.getAction());
        assertEquals(ADMIN_SUBJECT, audit.getActorUsername());
        assertEquals(CLIENT_IP, audit.getActorIp());
        assertEquals(byteCount, audit.getByteCount());
        assertNull(audit.getReportRequest());
        assertTrue(audit.getCorrelationId() != null && !audit.getCorrelationId().isBlank());

        JsonNode filter = audit.getFilterPayload();
        assertEquals(apex.id(), filter.get("lspId").asText());
        assertEquals("2026-03-01", filter.get("disbursalDateFrom").asText());
        assertEquals("2026-03-31", filter.get("disbursalDateTo").asText());
    }

    @Test
    void asyncReportDownloadWritesMisRequestDownloadedAuditRow() throws Exception {
        LspFixture apex = createLsp("APEX");
        ProductFixture product = createProduct();
        mapProductToLsps(product.id(), apex.id());

        JsonNode loan = createApplication(apex.id(), product.id(), "APEX-ASYNC-AUDIT", "ABCDE1234F");
        approveLoan(loan.get("id").asText());
        disburseLoan(loan.get("id").asText());
        setDisbursedAt(loan.get("id").asText(), LocalDate.of(2026, 3, 10));

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
                .andReturn();

        String requestId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        reportRequestService.processPendingRequests(10);

        MvcResult downloadResult = mockMvc.perform(get("/api/v1/internal/reports/requests/{requestId}/download", requestId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isOk())
                .andReturn();

        int byteCount = downloadResult.getResponse().getContentAsByteArray().length;
        ReportAccessAudit audit = latestAuditRow();

        assertEquals(ReportAccessAuditAction.MIS_REQUEST_DOWNLOADED, audit.getAction());
        assertEquals(ADMIN_SUBJECT, audit.getActorUsername());
        assertEquals(CLIENT_IP, audit.getActorIp());
        assertEquals(byteCount, audit.getByteCount());
        assertEquals(UUID.fromString(requestId), audit.getReportRequest().getId());

        JsonNode filter = audit.getFilterPayload();
        assertEquals(apex.id(), filter.get("lspId").asText());
        assertEquals("2026-03-01", filter.get("disbursalDateFrom").asText());
        assertEquals("2026-03-31", filter.get("disbursalDateTo").asText());
    }

    @Test
    void failedDownloadsDoNotWriteAuditRows() throws Exception {
        long before = reportAccessAuditRepository.count();

        mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis")
                        .with(systemAdmin())
                        .queryParam("disbursalDateFrom", "2026-04-10")
                        .queryParam("disbursalDateTo", "2026-04-01"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/internal/reports/requests/{requestId}/download", UUID.randomUUID())
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest());

        assertEquals(before, reportAccessAuditRepository.count());
    }

    @Test
    void previewSummaryAndListEndpointsDoNotWriteAuditRows() throws Exception {
        LspFixture apex = createLsp("APEX");
        ProductFixture product = createProduct();
        mapProductToLsps(product.id(), apex.id());

        JsonNode loan = createApplication(apex.id(), product.id(), "APEX-PERIM-001", "ABCDE1234F");
        approveLoan(loan.get("id").asText());
        disburseLoan(loan.get("id").asText());
        setDisbursedAt(loan.get("id").asText(), LocalDate.of(2026, 3, 10));

        long before = reportAccessAuditRepository.count();

        mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis/preview")
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis/summary")
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/reports/requests")
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        assertEquals(before, reportAccessAuditRepository.count());
    }

    @Test
    void byteCountMatchesResponseBodyLength() throws Exception {
        LspFixture apex = createLsp("APEX");
        ProductFixture product = createProduct();
        mapProductToLsps(product.id(), apex.id());

        JsonNode loan = createApplication(apex.id(), product.id(), "APEX-BYTES-001", "ABCDE1234F");
        approveLoan(loan.get("id").asText());
        disburseLoan(loan.get("id").asText());
        setDisbursedAt(loan.get("id").asText(), LocalDate.of(2026, 3, 10));

        MvcResult downloadResult = mockMvc.perform(get("/api/v1/internal/reports/portfolio-mis")
                        .with(systemAdmin())
                        .queryParam("lspId", apex.id()))
                .andExpect(status().isOk())
                .andReturn();

        byte[] body = downloadResult.getResponse().getContentAsByteArray();
        assertEquals(body.length, latestAuditRow().getByteCount());
    }

    private ReportAccessAudit latestAuditRow() {
        return reportAccessAuditRepository.findAll().stream()
                .max(Comparator.comparing(ReportAccessAudit::getCreatedAt))
                .orElseThrow();
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
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for approval", systemAdmin());
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for report audit", systemAdmin());
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
                                com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus.SUBMITTED,
                                "Uploaded for report audit flow",
                                "ops.user",
                                item.getFileName(),
                                item.getFileReference(),
                                item.getSourceReference(),
                                item.getContentType()
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
        return jwt().jwt(token -> token.subject(ADMIN_SUBJECT).claim("roles", List.of("SYSTEM_ADMIN")))
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
