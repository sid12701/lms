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
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
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
class AuditExplorerControllerReportAccessStreamTest {

  private static final String ADMIN_SUBJECT = "ops.admin";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;
  @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    integrationTestDatabaseCleaner.cleanIntegrationTestData();
  }

  @Test
  void reportAccessStreamSurfacesMisCsvDownloadRow() throws Exception {
    String lspId = createLsp();
    String productId = createProduct();
    mapProductToLsp(productId, lspId);

    JsonNode loan =
        createApplication(lspId, productId, "RPT-AUDIT-" + UUID.randomUUID(), "ABCDE1234F");
    approveAndDisburse(loan.get("id").asText(), LocalDate.of(2026, 3, 10));

    mockMvc
        .perform(
            get("/api/v1/internal/reports/portfolio-mis")
                .with(systemAdmin())
                .header("X-Forwarded-For", "203.0.113.77")
                .queryParam("lspId", lspId)
                .queryParam("disbursalDateFrom", "2026-03-01")
                .queryParam("disbursalDateTo", "2026-03-31"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/v1/internal/admin/audit-events")
                .with(systemAdmin())
                .queryParam("streams", "REPORT_ACCESS")
                .queryParam("lspId", lspId)
                .queryParam("paginationDetails", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(1))
        .andExpect(jsonPath("$.items[0].stream").value("REPORT_ACCESS"))
        .andExpect(jsonPath("$.items[0].action").value("MIS_CSV_DOWNLOADED"))
        .andExpect(jsonPath("$.items[0].actorUsername").value(ADMIN_SUBJECT))
        .andExpect(jsonPath("$.items[0].lspId").value(lspId))
        .andExpect(jsonPath("$.items[0].detail.reportType").value("PORTFOLIO_MIS"))
        .andExpect(jsonPath("$.items[0].detail.byteCount").isNumber())
        .andExpect(jsonPath("$.items[0].detail.filterPayload.lspId").value(lspId))
        .andExpect(jsonPath("$.items[0].detail.actorIp").value("203.0.113.77"))
        .andExpect(jsonPath("$.items[0].summary").value(org.hamcrest.Matchers.containsString("Portfolio MIS CSV downloaded")));
  }

  @Test
  void loanApplicationFilterExcludesReportAccessStream() throws Exception {
    String lspId = createLsp();
    String productId = createProduct();
    mapProductToLsp(productId, lspId);

    JsonNode loan =
        createApplication(lspId, productId, "RPT-EXCL-" + UUID.randomUUID(), "ABCDE1234F");
    String applicationId = loan.get("id").asText();
    approveAndDisburse(applicationId, LocalDate.of(2026, 3, 10));

    mockMvc
        .perform(
            get("/api/v1/internal/reports/portfolio-mis")
                .with(systemAdmin())
                .queryParam("lspId", lspId))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/v1/internal/admin/audit-events")
                .with(systemAdmin())
                .queryParam("streams", "REPORT_ACCESS,APPLICATION")
                .queryParam("loanApplicationId", applicationId)
                .queryParam("paginationDetails", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.items[?(@.stream == 'REPORT_ACCESS')]").isEmpty());
  }

  private void approveAndDisburse(String applicationId, LocalDate disbursedDate) throws Exception {
    transition(applicationId, "AWAITING_APPROVAL", "Ready");
    markAllRequiredKycDocumentsVerified(applicationId);
    transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved");
    mockMvc
        .perform(
            post(
                    "/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests",
                    applicationId)
                .with(systemAdmin()))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(
                    "/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome",
                    applicationId)
                .with(systemAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
        .andExpect(status().isOk());
    jdbcTemplate.update(
        "update loan_account set disbursed_at = ? where loan_application_id = ?",
        Timestamp.from(
            OffsetDateTime.of(disbursedDate.atStartOfDay(), ZoneOffset.UTC).toInstant()),
        UUID.fromString(applicationId));
  }

  private String createLsp() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/internal/admin/lsps")
                    .with(systemAdmin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "code",
                                "RPT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                                "name",
                                "Report Explorer LSP",
                                "status",
                                "ACTIVE"))))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private String createProduct() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/internal/admin/products")
                    .with(productAdmin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "code",
                                "PROD-"
                                    + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name",
                                "Report audit product",
                                "minPrincipal",
                                new BigDecimal("5000.00"),
                                "maxPrincipal",
                                new BigDecimal("250000.00"),
                                "interestRate",
                                new BigDecimal("18.50"),
                                "processingFeeRate",
                                new BigDecimal("2.25"),
                                "minTenureMonths",
                                6,
                                "maxTenureMonths",
                                24,
                                "status",
                                "ACTIVE"))))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private void mapProductToLsp(String productId, String lspId) throws Exception {
    mockMvc
        .perform(
            put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                .with(productAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
        .andExpect(status().isOk());
  }

  private JsonNode createApplication(
      String lspId, String productId, String externalLoanId, String borrowerPan)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/internal/ops/loan-applications")
                    .with(opsUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            loanApplicationPayload(lspId, productId, externalLoanId, borrowerPan))))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private static Map<String, Object> loanApplicationPayload(
      String lspId, String productId, String externalLoanId, String borrowerPan) {
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
    return "9" + String.format("%09d", hash % 1_000_000_000);
  }

  private void transition(String applicationId, String targetStatus, String note)
      throws Exception {
    mockMvc
        .perform(
            post(
                    "/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions",
                    applicationId)
                .with(systemAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("targetStatus", targetStatus, "note", note))))
        .andExpect(status().isOk());
  }

  private void markAllRequiredKycDocumentsVerified(String applicationId) {
    loanApplicationDocumentChecklistRepository
        .findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
        .forEach(
            item -> {
              if (item.isRequired()) {
                item.update(
                    LoanApplicationDocumentChecklistStatus.SUBMITTED,
                    "Uploaded for report audit explorer test",
                    "ops.user",
                    item.getFileName(),
                    item.getFileReference(),
                    item.getSourceReference(),
                    item.getContentType());
                loanApplicationDocumentChecklistRepository.save(item);
              }
            });
  }

  private static JwtRequestPostProcessor systemAdmin() {
    return jwt()
        .jwt(token -> token.subject(ADMIN_SUBJECT).claim("roles", List.of("SYSTEM_ADMIN")))
        .authorities(() -> "ROLE_SYSTEM_ADMIN");
  }

  private static JwtRequestPostProcessor productAdmin() {
    return jwt()
        .jwt(token -> token.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
        .authorities(() -> "ROLE_PRODUCT_ADMIN");
  }

  private static JwtRequestPostProcessor opsUser() {
    return jwt()
        .jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
        .authorities(() -> "ROLE_OPS_USER");
  }
}
