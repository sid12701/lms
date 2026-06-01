package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.repo.ApiClientAuditEventRepository;
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
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIsolationPostgresIntegrationTest extends PostgresDataJpaTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    private LoanApplicationPiiRevealAuditRepository loanApplicationPiiRevealAuditRepository;

    @Autowired
    private LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;

    @Autowired
    private LoanApplicationIntakeAuditRepository loanApplicationIntakeAuditRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private ApiClientAuditEventRepository apiClientAuditEventRepository;

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
    private LspAuditEventRepository lspAuditEventRepository;

    @Autowired
    private com.bhawana.lms.repo.LoanDisbursementBankMismatchLogRepository loanDisbursementBankMismatchLogRepository;

    @Autowired
    private com.bhawana.lms.repo.BorrowerBankDetailsUpdateAuditRepository borrowerBankDetailsUpdateAuditRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("delete from report_request");
        loanDisbursementBankMismatchLogRepository.deleteAllInBatch();
        borrowerBankDetailsUpdateAuditRepository.deleteAllInBatch();
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
        loanApplicationIntakeAuditRepository.deleteAllInBatch();
        loanApplicationRepository.deleteAllInBatch();
        borrowerRepository.deleteAllInBatch();
        apiClientAuditEventRepository.deleteAllInBatch();
        apiClientRepository.deleteAllInBatch();
        loanProductAuditEventRepository.deleteAllInBatch();
        loanProductLspMappingRepository.deleteAllInBatch();
        loanProductRepository.deleteAllInBatch();
        lspAuditEventRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }

    @Test
    void tenantConnectionOnlyReadsOwnReportRequestsEvenWithoutWhereClause() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");

        UUID apexRequestId = UUID.randomUUID();
        UUID northRequestId = UUID.randomUUID();
        UUID globalRequestId = UUID.randomUUID();
        createReportRequest(apexRequestId, UUID.fromString(apex.id()), "apex.reports");
        createReportRequest(northRequestId, UUID.fromString(north.id()), "north.reports");
        createReportRequest(globalRequestId, null, "ops.reports");

        assertEquals(3, queryCountAsAdmin("report_request"));
        org.assertj.core.api.Assertions.assertThat(queryReportRequestIdsAsTenant(UUID.fromString(apex.id())))
                .containsExactly(apexRequestId);
        org.assertj.core.api.Assertions.assertThat(queryReportRequestIdsAsTenant(UUID.fromString(north.id())))
                .containsExactly(northRequestId);
    }

    @Test
    void samePanAcrossTwoLspsCreatesSeparateBorrowerSnapshotsAndTenantListsStayIsolated() throws Exception {
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

        JsonNode apexApplication = createExternalApplication(apexAccessToken, apex.id(), apexProduct.id(), "APEX-RLS-001", "ABCDE1234F");
        JsonNode northApplication = createExternalApplication(northAccessToken, north.id(), northProduct.id(), "NORTH-RLS-001", "ABCDE1234F");

        assertEquals(apexApplication.get("borrowerId").asText(), northApplication.get("borrowerId").asText());
        assertEquals(1L, borrowerRepository.count());
        assertEquals(2L, loanApplicationRepository.count());

        JsonNode apexList = readJson(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + apexAccessToken))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode northList = readJson(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + northAccessToken))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals(1, apexList.size());
        assertEquals("APEX-RLS-001", apexList.get(0).get("lspLoanId").asText());
        assertEquals(1, northList.size());
        assertEquals("NORTH-RLS-001", northList.get(0).get("lspLoanId").asText());
        assertEquals(1, queryCountAsTenant(UUID.fromString(apex.id()), "borrower"));
        assertEquals(1, queryCountAsTenant(UUID.fromString(north.id()), "borrower"));
    }

    @Test
    void tenantRlsFailsClosedWithoutTenantContextAndAdminPathStillReadsPrimaryAndChildTables() throws Exception {
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

        createExternalApplication(apexAccessToken, apex.id(), apexProduct.id(), "APEX-RLS-010", "ABCDE1234F", "9999999999");
        createExternalApplication(northAccessToken, north.id(), northProduct.id(), "NORTH-RLS-010", "ZXCVB1234N", "8888888888");

        assertEquals(2, queryCountAsAdmin("loan_application"));
        assertEquals(2, queryCountAsAdmin("borrower"));
        assertEquals(2, queryCountAsAdmin("loan_application_intake_audit"));

        assertEquals(1, queryCountAsTenant(UUID.fromString(apex.id()), "loan_application"));
        assertEquals(1, queryCountAsTenant(UUID.fromString(apex.id()), "borrower"));
        assertEquals(1, queryCountAsTenant(UUID.fromString(apex.id()), "loan_application_intake_audit"));

        assertEquals(1, queryCountAsTenant(UUID.fromString(north.id()), "loan_application"));
        assertEquals(1, queryCountAsTenant(UUID.fromString(north.id()), "borrower"));
        assertEquals(1, queryCountAsTenant(UUID.fromString(north.id()), "loan_application_intake_audit"));

        DataAccessException exception = assertThrows(
                DataAccessException.class,
                () -> queryCountAsTenant(null, "loan_application")
        );
        String message = exception.getMostSpecificCause() == null
                ? exception.getMessage()
                : exception.getMostSpecificCause().getMessage();
        org.assertj.core.api.Assertions.assertThat(message)
                .containsAnyOf("app.current_lsp_id", "invalid input syntax for type uuid");
    }

    private int queryCountAsAdmin(String tableName) {
        return transactionTemplate().execute(status ->
                jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class)
        );
    }

    private int queryCountAsTenant(UUID lspId, String tableName) {
        TenantDataAccessContextHolder.useTenant(lspId);
        try {
            return transactionTemplate().execute(status ->
                    jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class)
            );
        } finally {
            TenantDataAccessContextHolder.clear();
        }
    }

    private List<UUID> queryReportRequestIdsAsTenant(UUID lspId) {
        TenantDataAccessContextHolder.useTenant(lspId);
        try {
            return transactionTemplate().execute(status ->
                    jdbcTemplate.queryForList("select id from report_request order by created_at", UUID.class)
            );
        } finally {
            TenantDataAccessContextHolder.clear();
        }
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private void createReportRequest(UUID requestId, UUID lspId, String requestedByUsername) {
        jdbcTemplate.update("""
                        insert into report_request (
                            id,
                            report_type,
                            status,
                            lsp_id,
                            requested_by_username
                        )
                        values (?, 'PORTFOLIO_MIS', 'PENDING', ?, ?)
                        """,
                requestId,
                lspId,
                requestedByUsername
        );
    }

    private JsonNode createExternalApplication(
            String accessToken,
            String lspId,
            String productId,
            String externalLoanId,
            String panNumber
    ) throws Exception {
        return createExternalApplication(accessToken, lspId, productId, externalLoanId, panNumber, "9999999999");
    }

    private JsonNode createExternalApplication(
            String accessToken,
            String lspId,
            String productId,
            String externalLoanId,
            String panNumber,
            String mobileNumber
    ) throws Exception {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("lspLoanId", externalLoanId);
        payload.put("fullName", "Borrower " + externalLoanId);
        payload.put("emailAddress", externalLoanId.toLowerCase() + "@example.com");
        payload.put("mobileNumber", mobileNumber);
        payload.put("dob", "1992-03-10");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Ramesh Sharma");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", panNumber);
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

        return readJson(mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode createApiClient(String lspId, String name) throws Exception {
        return readJson(mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn());
    }

    private String issueClientCredentialsToken(String clientId, String clientSecret) throws Exception {
        JsonNode tokenResponse = readJson(mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthController.ClientCredentialsRequest(
                                clientId,
                                clientSecret
                        ))))
                .andExpect(status().isOk())
                .andReturn());
        return tokenResponse.get("accessToken").asText();
    }

    private ProductFixture createProduct(String status) throws Exception {
        String code = "PRODUCT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        JsonNode product = readJson(mockMvc.perform(post("/api/v1/internal/admin/products")
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
                .andReturn());
        return new ProductFixture(product.get("id").asText(), product.get("code").asText());
    }

    private LspFixture createLsp(String status) throws Exception {
        String code = "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        JsonNode lsp = readJson(mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn());
        return new LspFixture(lsp.get("id").asText(), lsp.get("code").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(jwt -> jwt.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private record ProductFixture(String id, String code) {
    }

    private record LspFixture(String id, String code) {
    }
}
