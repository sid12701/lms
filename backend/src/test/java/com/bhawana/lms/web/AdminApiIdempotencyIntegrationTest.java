package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.AppRoleRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LoanProductRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.ReportRequestRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class AdminApiIdempotencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanApplicationStatusTransitionRepository statusTransitionRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanDisbursementRequestLogRepository disbursementRequestLogRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private LoanProductRepository loanProductRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppRoleRepository appRoleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @Autowired
    private AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        adminApiIdempotencyRecordRepository.deleteAll();
    }

    @Test
    void statusTransitionWithSameKeyReplaysWithoutDuplicateTransition() throws Exception {
        String applicationId = createOpsApplication();
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(Map.of(
                "targetStatus", "AWAITING_APPROVAL",
                "note", "Moved for idempotency test"
        ));

        MvcResult first = mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions",
                        applicationId)
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions",
                        applicationId)
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(
                        objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText()));

        assertEquals(1, statusTransitionRepository.findAll().stream()
                .filter(row -> row.getLoanApplication().getId().equals(UUID.fromString(applicationId)))
                .count());
    }

    @Test
    void disbursementInitiateWithSameKeyRunsOnce() throws Exception {
        String applicationId = createApprovedApplication();
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests",
                        applicationId)
                        .with(systemAdmin())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests",
                        applicationId)
                        .with(systemAdmin())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk());

        assertEquals(1, disbursementRequestLogRepository.findAll().stream()
                .filter(row -> row.getLoanAccount().getLoanApplication().getId().equals(UUID.fromString(applicationId)))
                .count());
    }

    @Test
    void alertAcknowledgeWithSameKeyReplaysWithoutDoubleAcknowledge() throws Exception {
        OpsAlert alert = seedAlert();
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(
                new OpsAlertController.AcknowledgeAlertRequest("Reviewed once"));

        mockMvc.perform(post("/api/v1/internal/alerts/{id}/acknowledge", alert.getId())
                        .with(opsUser())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        mockMvc.perform(post("/api/v1/internal/alerts/{id}/acknowledge", alert.getId())
                        .with(opsUser())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        assertEquals(1, adminApiIdempotencyRecordRepository.count());
    }

    @Test
    void productCreateWithSameKeyDoesNotDuplicateProduct() throws Exception {
        String key = UUID.randomUUID().toString();
        String code = "IDMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String body = objectMapper.writeValueAsString(Map.of(
                "code", code,
                "name", "Idempotent Product",
                "minPrincipal", new BigDecimal("5000.00"),
                "maxPrincipal", new BigDecimal("250000.00"),
                "interestRate", new BigDecimal("18.50"),
                "processingFeeRate", new BigDecimal("2.25"),
                "minTenureMonths", 6,
                "maxTenureMonths", 24,
                "status", "ACTIVE"
        ));

        MvcResult first = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(
                        objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText()));

        assertEquals(1, loanProductRepository.findAll().stream()
                .filter(product -> code.equals(product.getCode()))
                .count());
    }

    @Test
    void userCreateWithSameKeyDoesNotDuplicateUser() throws Exception {
        ensureOpsUserRoleExists();
        String key = UUID.randomUUID().toString();
        String username = "idmp-user-" + UUID.randomUUID().toString().substring(0, 8);
        String body = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "TempPassword123!",
                "status", "ACTIVE",
                "roles", List.of("OPS_USER")
        ));

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertEquals(1, appUserRepository.findAll().stream()
                .filter(user -> username.equals(user.getUsername()))
                .count());
    }

    @Test
    void reportRequestWithSameKeyReplaysAndAbsentKeyCreatesTwo() throws Exception {
        String lspId = createLsp().id();
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(Map.of(
                "lspId", lspId,
                "disbursalDateFrom", LocalDate.of(2024, 1, 1).toString(),
                "disbursalDateTo", LocalDate.of(2024, 12, 31).toString(),
                "recipientEmail", "reports@example.com"
        ));

        MvcResult first = mockMvc.perform(post("/api/v1/internal/reports/portfolio-mis/requests")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/internal/reports/portfolio-mis/requests")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));

        assertEquals(1, reportRequestRepository.count());

        mockMvc.perform(post("/api/v1/internal/reports/portfolio-mis/requests")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertEquals(2, reportRequestRepository.count());
    }

    @Test
    void reusedKeyWithDifferentBodyReturnsConflict() throws Exception {
        String applicationId = createOpsApplication();
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions",
                        applicationId)
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "First body"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions",
                        applicationId)
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Different body"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidIdempotencyKeyReturnsBadRequest() throws Exception {
        String applicationId = createOpsApplication();

        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions",
                        applicationId)
                        .with(systemAdmin())
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Should fail validation"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPasswordReplayOmitsTemporaryPassword() throws Exception {
        AppUser user = seedManagedUser();
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", user.getId())
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .header("X-Forwarded-For", "203.0.113.10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isString())
                .andReturn();

        MvcResult replay = mockMvc.perform(post("/api/v1/internal/admin/users/{userId}/reset-password", user.getId())
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .header("X-Forwarded-For", "203.0.113.10"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertTrue(replayJson.get("temporaryPassword") == null || replayJson.get("temporaryPassword").isNull());

        String tempPassword = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("temporaryPassword").asText();
        String storedBody = adminApiIdempotencyRecordRepository.findAll().get(0).getResponseBody();
        assertTrue(!storedBody.contains(tempPassword));
    }

    private String createOpsApplication() throws Exception {
        LspFixture lsp = createLsp();
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lsp.id());
        payload.put("productId", product.id());
        payload.put("externalLoanId", "EXT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("sourceChannel", "OPS_PORTAL");
        payload.put("borrowerPan", String.format("ABCDE%04dF", java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000)));
        payload.put("borrowerFullName", "Idempotency Borrower");
        payload.put("borrowerMobile", "9" + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000)));
        payload.put("borrowerEmail", "borrower-" + UUID.randomUUID() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1990, 1, 1));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("50000.00"));
        payload.put("requestedAmount", new BigDecimal("100000.00"));
        payload.put("tenureMonths", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createApprovedApplication() throws Exception {
        String applicationId = createOpsApplication();
        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions",
                        applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Moved for disbursement idempotency"
                        ))))
                .andExpect(status().isOk());
        markKycComplete(applicationId);
        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions",
                        applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Approved for disbursement idempotency"
                        ))))
                .andExpect(status().isOk());
        return applicationId;
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
                            "Uploaded for idempotency test",
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

    private OpsAlert seedAlert() {
        return TenantScopedExecution.callAsAdmin(() -> opsAlertRepository.save(new OpsAlert(
                OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                OpsAlertSeverity.HIGH,
                "Test alert",
                "Idempotency test alert",
                "SYSTEM",
                null,
                "corr-idempotency",
                null
        )));
    }

    private AppUser seedManagedUser() {
        AppRole opsRole = appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).stream()
                .findFirst()
                .orElseGet(() -> appRoleRepository.save(new AppRole(RoleCode.OPS_USER, "Ops user")));
        return appUserRepository.save(new AppUser(
                "reset-target-" + UUID.randomUUID().toString().substring(0, 8),
                "reset-target-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                passwordEncoder.encode("OriginalPassword123!"),
                UserStatus.ACTIVE,
                null,
                Set.of(opsRole)
        ));
    }

    private void ensureOpsUserRoleExists() {
        if (appRoleRepository.findByCodeIn(List.of(RoleCode.OPS_USER)).isEmpty()) {
            appRoleRepository.save(new AppRole(RoleCode.OPS_USER, "Ops user"));
        }
    }

    private LspFixture createLsp() throws Exception {
        String code = "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new LspFixture(json.get("id").asText(), json.get("code").asText());
    }

    private ProductFixture createProduct() throws Exception {
        String code = "PRD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
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
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new ProductFixture(json.get("id").asText(), json.get("code").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
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

    private record LspFixture(String id, String code) {
    }

    private record ProductFixture(String id, String code) {
    }
}
